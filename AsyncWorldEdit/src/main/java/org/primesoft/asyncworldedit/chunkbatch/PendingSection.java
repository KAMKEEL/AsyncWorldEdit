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

import java.util.Arrays;

/**
 * Pending block changes for a single 16x16x16 chunk section.
 *
 * The buffer stores the last queued (id, data, notify) per section index,
 * so multiple writes to the same position within one batch window keep
 * sequential semantics (last write wins). The apply methods copy the buffer
 * into raw chunk section arrays for either the vanilla 1.7.10 layout
 * (LSB byte array + MSB nibble array + metadata nibble array) or the
 * NotEnoughIDs 16 bit layout (short id array + metadata nibble array).
 *
 * Pure logic: no Bukkit/NMS imports.
 *
 * @author KAMKEEL
 */
public final class PendingSection {

    /**
     * Callback for every applied block whose stored id or data changed
     */
    public interface IChangedBlockVisitor {

        /**
         * @param index section index of the changed block
         * @param oldId block id previously stored in the section
         * @param newId block id that was written
         */
        void changed(int index, int oldId, int newId);
    }

    /**
     * Callback for pending blocks that can not be represented in the
     * target section layout (id too large). These must be placed through
     * the classic per block path.
     */
    public interface IOverflowVisitor {

        /**
         * @param index section index of the block
         * @param id the block id
         * @param data the block metadata
         * @param notify the notify flag the block was queued with
         */
        void overflow(int index, int id, int data, boolean notify);
    }

    /**
     * Pending slots, {@link SectionMath#EMPTY_SLOT} when unused
     */
    private final int[] m_slots;

    /**
     * Sequence number of the last write per slot. Used to replay small
     * batches through the classic path in insertion order (WorldEdit
     * queues attachments after their supports; coordinate order replay
     * would pop them off).
     */
    private final int[] m_seq;

    /**
     * Number of used slots
     */
    private int m_count;

    /**
     * Number of used slots holding a non air block
     */
    private int m_nonAirCount;

    /**
     * Bitset marking slots whose pending value is CONDITIONAL: it is only
     * written when the pre-write world value matches this section's match
     * condition (see {@link #setConditional}). Lazily allocated - plain
     * fills never pay for it.
     */
    private long[] m_condBits;

    /**
     * Number of conditional slots
     */
    private int m_condCount;

    /**
     * The block id conditional slots match against, -1 while no condition
     * is installed. One condition per section: the fast lane compiles one
     * operation per job, so a section never sees two different conditions
     * (a second condition is refused and the compiler aborts to the per
     * block lane).
     */
    private int m_matchId = -1;

    /**
     * The metadata conditional slots match against, -1 = any (wildcard)
     */
    private int m_matchData = -1;

    public PendingSection() {
        m_slots = new int[SectionMath.SECTION_SIZE];
        m_seq = new int[SectionMath.SECTION_SIZE];
        Arrays.fill(m_slots, SectionMath.EMPTY_SLOT);
    }

    /**
     * Number of positions with a pending block
     */
    public int getCount() {
        return m_count;
    }

    /**
     * Number of positions with a pending non air block
     */
    public int getNonAirCount() {
        return m_nonAirCount;
    }

    /**
     * Store a pending block. Overwrites any previous pending block at the
     * same index (last write wins). The sequence number defaults to 0;
     * use {@link #set(int, int, int, boolean, int)} when insertion order
     * replay matters.
     */
    public void set(int index, int id, int data, boolean notify) {
        set(index, id, data, notify, 0);
    }

    /**
     * Store a pending block. Overwrites any previous pending block at the
     * same index (last write wins); the slot takes the sequence number of
     * this (its last) write.
     */
    public void set(int index, int id, int data, boolean notify, int seq) {
        int old = m_slots[index];
        if (old == SectionMath.EMPTY_SLOT) {
            m_count++;
            if (id != 0) {
                m_nonAirCount++;
            }
        } else {
            boolean wasAir = SectionMath.slotId(old) == 0;
            boolean isAir = id == 0;
            if (wasAir && !isAir) {
                m_nonAirCount++;
            } else if (!wasAir && isAir) {
                m_nonAirCount--;
            }
        }

        m_slots[index] = SectionMath.encodeSlot(id, data, notify);
        m_seq[index] = seq;
        //An unconditional write over a conditional slot wins outright:
        //the conditional intent is dropped (last write wins)
        clearConditionalBit(index);
    }

    /**
     * Store a CONDITIONAL pending block: at flush time the (id, data) is
     * only written when the pre-write world value at this position matches
     * (matchId, matchData); a non-match writes nothing and is never
     * captured for undo (see PendingChunk.resolveConditionals).
     *
     * Semantics against earlier writes of the same job (last write wins,
     * matching the per block lane's read-your-writes replace exactly):
     * <ul>
     * <li>empty slot: stored conditional;</li>
     * <li>existing UNCONDITIONAL pending value: the condition is resolved
     * NOW against that value - the per block lane's mask would read it
     * through the overlay. A match stores the replacement (unconditional),
     * a non-match leaves the pending value untouched;</li>
     * <li>existing conditional slot (same condition, enforced below): the
     * replacement value and sequence are overwritten.</li>
     * </ul>
     *
     * @param matchId the block id the pre-write value must have (0..0xFFFF)
     * @param matchData the metadata the pre-write value must have, -1 = any
     * @return false when the section already holds conditional slots with a
     * DIFFERENT condition - nothing is stored and the caller must abort the
     * fast lane (one operation per job means this never happens in
     * production; the guard keeps the buffer sound if it ever does)
     */
    public boolean setConditional(int index, int id, int data, boolean notify,
            int seq, int matchId, int matchData) {
        if (m_condCount > 0 && (m_matchId != matchId || m_matchData != matchData)) {
            return false;
        }

        final int old = m_slots[index];
        if (old != SectionMath.EMPTY_SLOT && !isConditional(index)) {
            //Resolve against the earlier pending value right now
            if (SectionMath.slotId(old) == matchId
                    && (matchData < 0 || SectionMath.slotData(old) == matchData)) {
                set(index, id, data, notify, seq);
            }
            return true;
        }

        set(index, id, data, notify, seq);
        m_matchId = matchId;
        m_matchData = matchData;
        if (m_condBits == null) {
            m_condBits = new long[SectionMath.SECTION_SIZE / 64];
        }
        final long bit = 1L << (index & 63);
        if ((m_condBits[index >> 6] & bit) == 0) {
            m_condBits[index >> 6] |= bit;
            m_condCount++;
        }
        return true;
    }

    /**
     * True when the pending value at an index is conditional
     */
    public boolean isConditional(int index) {
        return m_condBits != null && (m_condBits[index >> 6] & (1L << (index & 63))) != 0;
    }

    /**
     * Number of conditional slots
     */
    public int getConditionalCount() {
        return m_condCount;
    }

    /**
     * The block id of this section's match condition (only meaningful
     * while {@link #getConditionalCount()} is non zero)
     */
    public int getMatchId() {
        return m_matchId;
    }

    /**
     * The metadata of this section's match condition, -1 = any (only
     * meaningful while {@link #getConditionalCount()} is non zero)
     */
    public int getMatchData() {
        return m_matchData;
    }

    /**
     * Turn a conditional slot into an ordinary pending slot (its condition
     * matched the pre-write world value). The slot value stays.
     */
    public void markResolved(int index) {
        clearConditionalBit(index);
    }

    /**
     * Drop the conditional bit of a slot (unconditional overwrite, clear,
     * or resolution)
     */
    private void clearConditionalBit(int index) {
        if (m_condBits == null) {
            return;
        }
        final long bit = 1L << (index & 63);
        if ((m_condBits[index >> 6] & bit) != 0) {
            m_condBits[index >> 6] &= ~bit;
            m_condCount--;
        }
    }

    /**
     * Remove a pending block (a classic path write is about to overwrite
     * this position, the stale pending value must not be flushed over it).
     *
     * @return true when a pending block was removed
     */
    public boolean clear(int index) {
        int old = m_slots[index];
        if (old == SectionMath.EMPTY_SLOT) {
            return false;
        }

        m_count--;
        if (SectionMath.slotId(old) != 0) {
            m_nonAirCount--;
        }
        m_slots[index] = SectionMath.EMPTY_SLOT;
        m_seq[index] = 0;
        clearConditionalBit(index);
        return true;
    }

    /**
     * The raw pending slot at an index, {@link SectionMath#EMPTY_SLOT} when
     * no block is pending there. Decode with the SectionMath slot functions.
     */
    public int getSlot(int index) {
        return m_slots[index];
    }

    /**
     * The sequence number of the last write to an index (only meaningful
     * when the slot is not empty)
     */
    public int getSeq(int index) {
        return m_seq[index];
    }

    /**
     * True when at least one pending block needs the MSB nibble array in
     * the vanilla layout (id &gt; 255 and representable in 12 bits)
     */
    public boolean needsMsb() {
        for (int i = 0; i < m_slots.length; i++) {
            int slot = m_slots[i];
            if (slot == SectionMath.EMPTY_SLOT) {
                continue;
            }
            int id = SectionMath.slotId(slot);
            if (id > 0xFF && id <= SectionMath.VANILLA_MAX_ID) {
                return true;
            }
        }
        return false;
    }

    /**
     * Apply the pending blocks to a vanilla layout section.
     *
     * @param lsb the LSB block id byte array (4096 entries), modified in
     * place
     * @param msb the MSB block id nibble array (2048 bytes) or null when the
     * section has none; when null, all pending ids must be &lt;= 255
     * (check {@link #needsMsb()} and create the array first). When present
     * the nibble is always written so stale MSB bits from a previous block
     * can not corrupt the new id.
     * @param meta the metadata nibble array (2048 bytes), modified in place
     * @param changedVisitor optional, called for every block whose stored
     * id or metadata changed
     * @param overflowVisitor called for pending blocks with ids that do not
     * fit the 12 bit layout; those are not written
     * @return number of blocks written into the arrays
     */
    public int applyVanilla(byte[] lsb, byte[] msb, byte[] meta,
            IChangedBlockVisitor changedVisitor, IOverflowVisitor overflowVisitor) {
        int written = 0;

        for (int index = 0; index < m_slots.length; index++) {
            int slot = m_slots[index];
            if (slot == SectionMath.EMPTY_SLOT) {
                continue;
            }

            int id = SectionMath.slotId(slot);
            int data = SectionMath.slotData(slot);

            if (id > SectionMath.VANILLA_MAX_ID || (id > 0xFF && msb == null)) {
                if (overflowVisitor != null) {
                    overflowVisitor.overflow(index, id, data, SectionMath.slotNotify(slot));
                }
                continue;
            }

            int oldId = lsb[index] & 0xFF;
            if (msb != null) {
                oldId |= SectionMath.nibbleGet(msb, index) << 8;
            }
            int oldData = SectionMath.nibbleGet(meta, index);

            lsb[index] = (byte) (id & 0xFF);
            if (msb != null) {
                SectionMath.nibbleSet(msb, index, (id >> 8) & 0xF);
            }
            SectionMath.nibbleSet(meta, index, data);
            written++;

            if (changedVisitor != null && (oldId != id || oldData != data)) {
                changedVisitor.changed(index, oldId, id);
            }
        }

        return written;
    }

    /**
     * Apply the pending blocks to a GTNH NotEnoughIds section where BOTH
     * ids and metadata are 16 bit short arrays (block16BArray /
     * block16BMetaArray). The vanilla metadata NibbleArray is DEAD on
     * those servers - writing it silently drops every block's metadata.
     *
     * @param ids16 the 16 bit block id array (4096 entries), modified in
     * place
     * @param meta16 the 16 bit metadata array (4096 entries), modified in
     * place
     * @param changedVisitor optional, called for every block whose stored
     * id or metadata changed
     * @param overflowVisitor called for pending blocks with ids over 16
     * bits; those are not written
     * @return number of blocks written into the arrays
     */
    public int applyId16(short[] ids16, short[] meta16,
            IChangedBlockVisitor changedVisitor, IOverflowVisitor overflowVisitor) {
        int written = 0;

        for (int index = 0; index < m_slots.length; index++) {
            int slot = m_slots[index];
            if (slot == SectionMath.EMPTY_SLOT) {
                continue;
            }

            int id = SectionMath.slotId(slot);
            int data = SectionMath.slotData(slot);

            if (id > SectionMath.ID16_MAX_ID) {
                if (overflowVisitor != null) {
                    overflowVisitor.overflow(index, id, data, SectionMath.slotNotify(slot));
                }
                continue;
            }

            int oldId = ids16[index] & 0xFFFF;
            int oldData = meta16[index] & 0xFFFF;

            ids16[index] = (short) id;
            meta16[index] = (short) data;
            written++;

            if (changedVisitor != null && (oldId != id || oldData != data)) {
                changedVisitor.changed(index, oldId, id);
            }
        }

        return written;
    }

    /**
     * Apply the pending blocks to a NotEnoughIDs 16 bit layout section.
     *
     * @param ids16 the 16 bit block id array (4096 entries), modified in
     * place. Ids are stored as raw shorts and must be read back with
     * {@code & 0xFFFF}.
     * @param meta the metadata nibble array (2048 bytes), modified in place
     * @param changedVisitor optional, called for every block whose stored
     * id or metadata changed
     * @param overflowVisitor called for pending blocks with ids over 16
     * bits; those are not written
     * @return number of blocks written into the arrays
     */
    public int applyId16(short[] ids16, byte[] meta,
            IChangedBlockVisitor changedVisitor, IOverflowVisitor overflowVisitor) {
        int written = 0;

        for (int index = 0; index < m_slots.length; index++) {
            int slot = m_slots[index];
            if (slot == SectionMath.EMPTY_SLOT) {
                continue;
            }

            int id = SectionMath.slotId(slot);
            int data = SectionMath.slotData(slot);

            if (id > SectionMath.ID16_MAX_ID) {
                if (overflowVisitor != null) {
                    overflowVisitor.overflow(index, id, data, SectionMath.slotNotify(slot));
                }
                continue;
            }

            int oldId = ids16[index] & 0xFFFF;
            int oldData = SectionMath.nibbleGet(meta, index);

            ids16[index] = (short) id;
            SectionMath.nibbleSet(meta, index, data);
            written++;

            if (changedVisitor != null && (oldId != id || oldData != data)) {
                changedVisitor.changed(index, oldId, id);
            }
        }

        return written;
    }
}
