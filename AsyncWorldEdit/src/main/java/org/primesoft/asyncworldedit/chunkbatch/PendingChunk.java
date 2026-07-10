/*
 * AsyncWorldEdit a performance improvement plugin for Minecraft WorldEdit plugin.
 * Copyright (c) 2014, SBPrime <https://github.com/SBPrime/>
 * Copyright (c) AsyncWorldEdit contributors
 *
 * All rights reserved.
 *
 * Redistribution in source, use in source and binary forms, with or without
 * modification, are permitted free of charge provided that the following
 * conditions are met:
 *
 * 1.  Redistributions of source code must retain the above copyright notice, this
 *     list of conditions and the following disclaimer.
 * 2.  Redistributions of source code, with or without modification, in any form
 *     other then free of charge is not allowed,
 * 3.  Redistributions of source code, with tools and/or scripts used to build the
 *     software is not allowed,
 * 4.  Redistributions of source code, with information on how to compile the software
 *     is not allowed,
 * 5.  Providing information of any sort (excluding information from the software page)
 *     on how to compile the software is not allowed,
 * 6.  You are allowed to build the software for your personal use,
 * 7.  You are allowed to build the software using a non public build server,
 * 8.  Redistributions in binary form in not allowed.
 * 9.  The original author is allowed to redistrubute the software in bnary form.
 * 10. Any derived work based on or containing parts of this software must reproduce
 *     the above copyright notice, this list of conditions and the following
 *     disclaimer in the documentation and/or other materials provided with the
 *     derived work.
 * 11. The original author of the software is allowed to change the license
 *     terms or the entire license of the software as he sees fit.
 * 12. The original author of the software is allowed to sublicense the software
 *     or its parts using any license terms he sees fit.
 * 13. By contributing to this project you agree that your contribution falls under this
 *     license.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package org.primesoft.asyncworldedit.chunkbatch;

/**
 * Pending block changes for a single chunk column, split into per section
 * buffers. Pure logic: no Bukkit/NMS imports.
 *
 * @author KAMKEEL
 */
public final class PendingChunk {

    /**
     * Visitor for {@link PendingChunk#forEachLastWriteOrder}
     */
    public interface IPendingBlockVisitor {

        /**
         * @param x world x coordinate
         * @param y world y coordinate
         * @param z world z coordinate
         * @param id the block id
         * @param data the block metadata
         * @param notify the notify flag the block was queued with
         * @param seq the sequence number of the position's last write
         */
        void visit(int x, int y, int z, int id, int data, boolean notify, int seq);
    }

    private final int m_cx;

    private final int m_cz;

    /**
     * Per section buffers, created lazily
     */
    private final PendingSection[] m_sections;

    /**
     * Total number of pending blocks in this chunk
     */
    private int m_count;

    /**
     * Number of allocated section buffers (memory bookkeeping)
     */
    private int m_sectionCount;

    /**
     * Chunk local fallback write sequence, used when the caller does not
     * provide one. The batch writer always provides a batch global
     * sequence so replay order is comparable across chunks.
     */
    private int m_writeSeq;

    /**
     * The sequence of the most recent write into this chunk (chunk level
     * last-write stamp). The streaming drain orders a job's chunks by this
     * value to pick the least-recently-written ones first.
     */
    private int m_lastWriteSeq;

    /**
     * The placer run index of the most recent write into this chunk,
     * stamped by the registry at buffer() time. The streaming drain flushes
     * a chunk untouched for stale-runs runs.
     */
    private int m_lastTouchRun;

    public PendingChunk(int cx, int cz) {
        m_cx = cx;
        m_cz = cz;
        m_sections = new PendingSection[SectionMath.SECTIONS_PER_CHUNK];
    }

    /**
     * The chunk x coordinate
     */
    public int getX() {
        return m_cx;
    }

    /**
     * The chunk z coordinate
     */
    public int getZ() {
        return m_cz;
    }

    /**
     * Total number of positions with a pending block
     */
    public int getCount() {
        return m_count;
    }

    /**
     * Store a pending block using the chunk local write sequence. Only
     * suitable when replay order across chunks does not matter.
     *
     * @return false when the coordinates do not belong to this chunk or y
     * is out of range (nothing is stored)
     */
    public boolean setBlock(int x, int y, int z, int id, int data, boolean notify) {
        return setBlock(x, y, z, id, data, notify, m_writeSeq++);
    }

    /**
     * Store a pending block with an explicit (batch global) write
     * sequence. The world coordinates must belong to this chunk and
     * 0 &lt;= y &lt;= 255 (the caller checks eligibility).
     *
     * @return false when the coordinates do not belong to this chunk or y
     * is out of range (nothing is stored)
     */
    public boolean setBlock(int x, int y, int z, int id, int data, boolean notify, int seq) {
        if (SectionMath.blockToChunk(x) != m_cx
                || SectionMath.blockToChunk(z) != m_cz
                || y < 0 || y > 255) {
            return false;
        }

        int sectionIdx = SectionMath.sectionOfY(y);
        PendingSection section = m_sections[sectionIdx];
        if (section == null) {
            section = new PendingSection();
            m_sections[sectionIdx] = section;
            m_sectionCount++;
        }

        int before = section.getCount();
        section.set(SectionMath.sectionIndex(x, y, z), id, data, notify, seq);
        m_count += section.getCount() - before;
        m_lastWriteSeq = seq;

        return true;
    }

    /**
     * Bulk fill: store the same pending (id, data) for every position of a
     * chunk-local box. The fast lane's compilation target - no per block
     * calls above this method, the inner loops are plain array writes.
     *
     * The box must lie inside this chunk (world coordinates) with
     * 0 &lt;= y0 &lt;= y1 &lt;= 255 and x0 &lt;= x1, z0 &lt;= z1; the CALLER
     * has already acquired the shared section budget for every section this
     * box newly allocates (see {@link #newSectionsInYRange}). Every filled
     * slot takes the same write sequence: the fill is one operation-level
     * write, and within equal sequences the classic replay's index order is
     * deterministic (attachments never come from fills).
     *
     * @return the number of slots that were EMPTY before this fill (the
     * growth of {@link #getCount()})
     */
    public int fillBox(int x0, int y0, int z0, int x1, int y1, int z1,
            int id, int data, boolean notify, int seq) {
        if (SectionMath.blockToChunk(x0) != m_cx || SectionMath.blockToChunk(x1) != m_cx
                || SectionMath.blockToChunk(z0) != m_cz || SectionMath.blockToChunk(z1) != m_cz
                || y0 < 0 || y1 > 255 || x0 > x1 || y0 > y1 || z0 > z1) {
            return 0;
        }

        int added = 0;
        for (int sy = SectionMath.sectionOfY(y0); sy <= SectionMath.sectionOfY(y1); sy++) {
            final int secBase = sy << 4;
            final int fy0 = Math.max(y0, secBase);
            final int fy1 = Math.min(y1, secBase + 15);

            PendingSection section = m_sections[sy];
            if (section == null) {
                section = new PendingSection();
                m_sections[sy] = section;
                m_sectionCount++;
            }

            final int before = section.getCount();
            for (int y = fy0; y <= fy1; y++) {
                for (int z = z0; z <= z1; z++) {
                    for (int x = x0; x <= x1; x++) {
                        section.set(SectionMath.sectionIndex(x, y, z), id, data, notify, seq);
                    }
                }
            }
            added += section.getCount() - before;
        }

        m_count += added;
        m_lastWriteSeq = seq;
        return added;
    }

    /**
     * Conditional bulk fill: store a CONDITIONAL pending (id, data) for
     * every position of a chunk-local box - the compilation target of an
     * eligible //replace. At flush time each slot is only written when the
     * pre-write world value matches (matchId, matchData); see
     * {@link #resolveConditionals}. Box contract and budget contract are
     * identical to {@link #fillBox}.
     *
     * @param matchId the block id the pre-write value must have
     * @param matchData the metadata the pre-write value must have, -1 = any
     * @return the number of newly used slots, or -1 when a covered section
     * already holds conditional slots with a DIFFERENT condition (nothing
     * is stored; the caller aborts the fast lane)
     */
    public int fillBoxConditional(int x0, int y0, int z0, int x1, int y1, int z1,
            int id, int data, boolean notify, int seq, int matchId, int matchData) {
        if (SectionMath.blockToChunk(x0) != m_cx || SectionMath.blockToChunk(x1) != m_cx
                || SectionMath.blockToChunk(z0) != m_cz || SectionMath.blockToChunk(z1) != m_cz
                || y0 < 0 || y1 > 255 || x0 > x1 || y0 > y1 || z0 > z1) {
            return 0;
        }

        //Condition compatibility precheck: nothing is stored when any
        //covered section already holds a DIFFERENT condition, so a refusal
        //never leaves partial state behind
        for (int sy = SectionMath.sectionOfY(y0); sy <= SectionMath.sectionOfY(y1); sy++) {
            final PendingSection section = m_sections[sy];
            if (section != null && section.getConditionalCount() > 0
                    && (section.getMatchId() != matchId
                    || section.getMatchData() != matchData)) {
                return -1;
            }
        }

        int added = 0;
        for (int sy = SectionMath.sectionOfY(y0); sy <= SectionMath.sectionOfY(y1); sy++) {
            final int secBase = sy << 4;
            final int fy0 = Math.max(y0, secBase);
            final int fy1 = Math.min(y1, secBase + 15);

            PendingSection section = m_sections[sy];
            if (section == null) {
                section = new PendingSection();
                m_sections[sy] = section;
                m_sectionCount++;
            }

            final int before = section.getCount();
            for (int y = fy0; y <= fy1; y++) {
                for (int z = z0; z <= z1; z++) {
                    for (int x = x0; x <= x1; x++) {
                        section.setConditional(SectionMath.sectionIndex(x, y, z),
                                id, data, notify, seq, matchId, matchData);
                    }
                }
            }
            added += section.getCount() - before;
        }

        m_count += added;
        m_lastWriteSeq = seq;
        return added;
    }

    /**
     * True when any section holds conditional slots (the flush must
     * resolve them against the pre-write world first)
     */
    public boolean hasConditional() {
        for (int s = 0; s < SectionMath.SECTIONS_PER_CHUNK; s++) {
            final PendingSection section = m_sections[s];
            if (section != null && section.getConditionalCount() > 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * Old value source for {@link #resolveConditionals} (injected reader
     * over the pre-write world)
     */
    public interface IOldValueReader {

        /**
         * @return the packed pre-write slot at a world position (encode
         * with SectionMath), or {@link SectionMath#EMPTY_SLOT} when it
         * cannot be read
         */
        int read(int x, int y, int z);
    }

    /**
     * Resolve every conditional slot against the pre-write world: a slot
     * whose old value matches its section's condition becomes an ordinary
     * pending slot (it will be written and undo-captured exactly like a
     * plain fill); a non-matching slot is CLEARED - it is never written,
     * never captured and never replayed. An unreadable old value clears
     * the slot too (never write what cannot be verified) and is counted
     * for the caller's one-time warning.
     *
     * Must run on the flush path BEFORE the undo capture and BEFORE any
     * write (direct section apply, classic replay, tile invalidation,
     * overflow collection) - afterwards the chunk contains only ordinary
     * slots and every downstream consumer works unchanged.
     *
     * @param reader the pre-write world reader
     * @return number of conditional slots whose old value could not be read
     */
    public int resolveConditionals(IOldValueReader reader) {
        final int bx = m_cx << 4;
        final int bz = m_cz << 4;
        int misses = 0;

        for (int s = 0; s < SectionMath.SECTIONS_PER_CHUNK; s++) {
            final PendingSection section = m_sections[s];
            if (section == null || section.getConditionalCount() == 0) {
                continue;
            }

            final int matchId = section.getMatchId();
            final int matchData = section.getMatchData();
            final int by = s << 4;

            for (int index = 0; index < SectionMath.SECTION_SIZE
                    && section.getConditionalCount() > 0; index++) {
                if (!section.isConditional(index)) {
                    continue;
                }

                final int old = reader.read(
                        bx + SectionMath.indexToX(index),
                        by + SectionMath.indexToY(index),
                        bz + SectionMath.indexToZ(index));

                if (old == SectionMath.EMPTY_SLOT) {
                    misses++;
                } else if (SectionMath.slotId(old) == matchId
                        && (matchData < 0 || SectionMath.slotData(old) == matchData)) {
                    section.markResolved(index);
                    continue;
                }

                if (section.clear(index)) {
                    m_count--;
                }
            }
        }

        return misses;
    }

    /**
     * Number of NEW section buffers a fill spanning [y0..y1] would
     * allocate. The registry acquires exactly this many shared budget
     * slots before calling {@link #fillBox}.
     */
    public int newSectionsInYRange(int y0, int y1) {
        if (y0 < 0 || y1 > 255 || y0 > y1) {
            return 0;
        }
        int missing = 0;
        for (int sy = SectionMath.sectionOfY(y0); sy <= SectionMath.sectionOfY(y1); sy++) {
            if (m_sections[sy] == null) {
                missing++;
            }
        }
        return missing;
    }

    /**
     * The write sequence of the most recent write into this chunk (0 when
     * nothing was ever stored). With the registry's monotonic global
     * sequence this orders a job's chunks from least to most recently
     * written.
     */
    public int getLastWriteSeq() {
        return m_lastWriteSeq;
    }

    /**
     * The placer run index of the most recent write into this chunk. Only
     * meaningful when the owner stamps it via {@link #setLastTouchRun}.
     */
    public int getLastTouchRun() {
        return m_lastTouchRun;
    }

    /**
     * Stamp the placer run index of the current write. Called by the
     * registry inside the chunk lock right after a successful store.
     */
    public void setLastTouchRun(int run) {
        m_lastTouchRun = run;
    }

    /**
     * Number of allocated section buffers. Each buffer costs a fixed
     * amount of memory (the slot and sequence arrays), so this is the
     * number the batch writer uses for its memory cap.
     */
    public int getSectionCount() {
        return m_sectionCount;
    }

    /**
     * True when storing a block at this y would allocate a new section
     * buffer
     */
    public boolean needsNewSection(int y) {
        return y >= 0 && y <= 255 && m_sections[SectionMath.sectionOfY(y)] == null;
    }

    /**
     * Remove the pending block at a world position (a classic path write
     * is about to overwrite it, the stale pending value must not be
     * flushed over the classic write).
     *
     * @return true when a pending block was removed
     */
    public boolean clear(int x, int y, int z) {
        if (SectionMath.blockToChunk(x) != m_cx
                || SectionMath.blockToChunk(z) != m_cz
                || y < 0 || y > 255) {
            return false;
        }

        PendingSection section = m_sections[SectionMath.sectionOfY(y)];
        if (section == null) {
            return false;
        }

        if (!section.clear(SectionMath.sectionIndex(x, y, z))) {
            return false;
        }

        m_count--;
        return true;
    }

    /**
     * Visit every pending block with its final value, ordered by the
     * sequence of each position's last write. This is the order the
     * classic replay must use: WorldEdit's reorder stage queues
     * attachments (torches, levers, rails) after their supports, so the
     * replay has to place the support first. A position rewritten later
     * in the window is visited at its last write position with its final
     * value.
     */
    public void forEachLastWriteOrder(IPendingBlockVisitor visitor) {
        if (m_count <= 0) {
            return;
        }

        //Encode (seq << 16 | section << 12 | index) so a plain sort on the
        //long values orders the pending blocks by their last write
        final long[] entries = new long[m_count];
        int n = 0;

        for (int s = 0; s < SectionMath.SECTIONS_PER_CHUNK; s++) {
            PendingSection section = m_sections[s];
            if (section == null) {
                continue;
            }
            for (int index = 0; index < SectionMath.SECTION_SIZE; index++) {
                if (section.getSlot(index) == SectionMath.EMPTY_SLOT) {
                    continue;
                }
                entries[n++] = ((long) section.getSeq(index) << 16)
                        | ((long) s << 12) | index;
            }
        }

        java.util.Arrays.sort(entries, 0, n);

        final int bx = m_cx << 4;
        final int bz = m_cz << 4;

        for (int i = 0; i < n; i++) {
            final long entry = entries[i];
            final int s = (int) ((entry >> 12) & 0xF);
            final int index = (int) (entry & 0xFFF);
            final int slot = m_sections[s].getSlot(index);

            visitor.visit(
                    bx + SectionMath.indexToX(index),
                    (s << 4) + SectionMath.indexToY(index),
                    bz + SectionMath.indexToZ(index),
                    SectionMath.slotId(slot), SectionMath.slotData(slot),
                    SectionMath.slotNotify(slot),
                    m_sections[s].getSeq(index));
        }
    }

    /**
     * The pending slot for a world position, {@link SectionMath#EMPTY_SLOT}
     * when nothing is pending there or the position is outside this chunk.
     * Decode with the SectionMath slot functions.
     */
    public int getPendingSlot(int x, int y, int z) {
        if (SectionMath.blockToChunk(x) != m_cx
                || SectionMath.blockToChunk(z) != m_cz
                || y < 0 || y > 255) {
            return SectionMath.EMPTY_SLOT;
        }

        return getPendingSlotLocal(SectionMath.blockToLocal(x), y,
                SectionMath.blockToLocal(z));
    }

    /**
     * The pending slot for a chunk local position (x and z 0..15, y world),
     * {@link SectionMath#EMPTY_SLOT} when nothing is pending there.
     *
     * A CONDITIONAL slot reads as empty: whether it will be written is
     * unknowable until the flush resolves it against the pre-write world,
     * so overlay reads (read-your-writes) must fall through to the world
     * - exactly what the per block lane's mask read would return before
     * the replace decided.
     */
    public int getPendingSlotLocal(int lx, int y, int lz) {
        if (y < 0 || y > 255 || lx < 0 || lx > 15 || lz < 0 || lz > 15) {
            return SectionMath.EMPTY_SLOT;
        }

        PendingSection section = m_sections[SectionMath.sectionOfY(y)];
        if (section == null) {
            return SectionMath.EMPTY_SLOT;
        }

        final int index = SectionMath.sectionIndex(lx, y, lz);
        if (section.isConditional(index)) {
            return SectionMath.EMPTY_SLOT;
        }
        return section.getSlot(index);
    }

    /**
     * The write sequence of the pending block at a world position. Only
     * meaningful when {@link #getPendingSlot} returned a non empty slot
     * for the same position.
     *
     * @return the sequence of the position's last write, 0 when nothing
     * is pending there
     */
    public int getPendingSeq(int x, int y, int z) {
        if (SectionMath.blockToChunk(x) != m_cx
                || SectionMath.blockToChunk(z) != m_cz
                || y < 0 || y > 255) {
            return 0;
        }

        PendingSection section = m_sections[SectionMath.sectionOfY(y)];
        if (section == null) {
            return 0;
        }

        return section.getSeq(SectionMath.sectionIndex(x, y, z));
    }

    /**
     * The pending section buffer, null when the section has no pending
     * blocks
     *
     * @param sectionIdx section number (0..15)
     */
    public PendingSection getSection(int sectionIdx) {
        return m_sections[sectionIdx];
    }
}
