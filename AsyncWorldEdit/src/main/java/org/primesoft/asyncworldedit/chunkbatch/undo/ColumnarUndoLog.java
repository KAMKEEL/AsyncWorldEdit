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
package org.primesoft.asyncworldedit.chunkbatch.undo;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.primesoft.asyncworldedit.chunkbatch.SectionMath;

/**
 * The columnar undo delta stream of one buffered job: per touched slot the
 * (oldId, oldData, newId, newData) columns, captured at flush time BEFORE
 * the section write, RLE-compressed over runs of consecutive slots with
 * identical value pairs, spooled to a per job temp file above a byte
 * threshold and replayed backward (undo) or forward (redo) without ever
 * loading the whole stream.
 *
 * Pure logic: no WorldEdit/Bukkit/NMS imports (chunkbatch conventions);
 * the WE ChangeSet adapter wraps this class.
 *
 * Correctness rules:
 * <ul>
 * <li>First-capture-per-slot: the undo target of a slot is the world value
 * before the job's FIRST write there. A per chunk-section bitset (512
 * bytes, kept for the whole job across window evictions) rejects
 * re-captures when streaming flushes the same section multiple times.</li>
 * <li>Segments are appended in flush order and carry the [firstSeq,
 * lastSeq] write-sequence range of their flush, so a composite change set
 * can merge them with the object change set by global sequence.</li>
 * <li>Replay order: backward = segments in reverse append order, runs in
 * reverse, slots of a run in reverse; forward is the exact mirror. Since a
 * slot is captured at most once, per-slot correctness does not depend on
 * cross-segment order.</li>
 * </ul>
 *
 * Threading: single writer (the main thread flush), replay after the job
 * finished; the class is intentionally NOT thread safe (the adapter
 * enforces the handoff).
 *
 * @author KAMKEEL
 */
public final class ColumnarUndoLog {

    /**
     * Replay visitor: one block change with world coordinates
     */
    public interface IUndoVisitor {

        /**
         * @param x world x
         * @param y world y
         * @param z world z
         * @param oldId old block id (the undo value)
         * @param oldData old block metadata
         * @param newId new block id (the redo value)
         * @param newData new block metadata
         */
        void change(int x, int y, int z, int oldId, int oldData, int newId, int newData);
    }

    /**
     * Ints per encoded run: (slotIndex<<16|count), (oldId<<16|oldData),
     * (newId<<16|newData)
     */
    static final int INTS_PER_RUN = 3;

    /**
     * One captured section flush: header + either in-memory runs or a
     * spool file offset
     */
    private static final class Segment {

        final int cx;
        final int cz;
        final int section;
        final int firstSeq;
        final int lastSeq;
        int runCount;
        int captureCount;
        /**
         * The encoded runs, null once spooled
         */
        int[] runs;
        /**
         * Offset of the run data in the spool file, -1 while in memory
         */
        long fileOffset = -1;

        Segment(int cx, int cz, int section, int firstSeq, int lastSeq) {
            this.cx = cx;
            this.cz = cz;
            this.section = section;
            this.firstSeq = firstSeq;
            this.lastSeq = lastSeq;
        }
    }

    /**
     * All captured segments in append (flush) order; spooled ones keep
     * only the header + file offset
     */
    private final List<Segment> m_segments = new ArrayList<Segment>();

    /**
     * First-capture bitsets: chunk key -> 16 sections -> 4096 bits. Kept
     * for the whole job (the memory anchor of correctness: 512 bytes per
     * touched section).
     */
    private final Map<Long, long[][]> m_captured = new HashMap<Long, long[][]>();

    /**
     * The spool file (created lazily on the first spill)
     */
    private final File m_spoolFile;

    /**
     * In-memory run bytes above which closed segments spill to the file
     */
    private final long m_spoolThresholdBytes;

    private DataOutputStream m_spoolOut;

    private long m_spoolLength;

    /**
     * Sum of in-memory (unspooled) run bytes
     */
    private long m_memoryBytes;

    /**
     * Total captured block changes
     */
    private long m_captureCount;

    /**
     * The open (being appended) segment, null between sections
     */
    private Segment m_open;

    private int[] m_openRuns;
    private int m_openRunCount;
    private int m_openCaptures;
    private long[] m_openBits;
    private int m_prevSlot;
    private int m_prevOld;
    private int m_prevNew;

    /**
     * Closed after {@link #close}; every mutator refuses
     */
    private boolean m_closed;

    /**
     * @param spoolFile per job temp file for the spilled segments (created
     * lazily, deleted by {@link #close})
     * @param spoolThresholdBytes in-memory run bytes above which closed
     * segments spill to the file
     */
    public ColumnarUndoLog(File spoolFile, long spoolThresholdBytes) {
        m_spoolFile = spoolFile;
        m_spoolThresholdBytes = Math.max(0, spoolThresholdBytes);
    }

    /**
     * Open a capture segment for one section flush. Captures must arrive
     * in ascending slot index order (the flush iterates the section
     * arrays in order).
     *
     * @param cx chunk x
     * @param cz chunk z
     * @param section section index (0..15)
     * @param firstSeq lowest write sequence of the flushed section
     * @param lastSeq highest write sequence of the flushed section
     */
    public void beginSection(int cx, int cz, int section, int firstSeq, int lastSeq) {
        if (m_closed || m_open != null) {
            throw new IllegalStateException(m_closed
                    ? "the undo log is closed" : "a section capture is already open");
        }
        m_open = new Segment(cx, cz, section, firstSeq, lastSeq);
        m_openRuns = new int[INTS_PER_RUN * 16];
        m_openRunCount = 0;
        m_openCaptures = 0;
        m_openBits = bitsOf(cx, cz, section);
        m_prevSlot = Integer.MIN_VALUE;
    }

    /**
     * Capture one slot of the open section (old value read BEFORE the
     * write). Silently skipped when the slot was already captured by an
     * earlier flush of this section (first capture wins).
     *
     * @param slotIndex slot in the section (0..4095), ascending per section
     * @param oldId old block id
     * @param oldData old metadata
     * @param newId new block id
     * @param newData new metadata
     */
    public void capture(int slotIndex, int oldId, int oldData, int newId, int newData) {
        if (m_open == null) {
            throw new IllegalStateException("no section capture is open");
        }

        final int word = slotIndex >> 6;
        final long bit = 1L << (slotIndex & 63);
        if ((m_openBits[word] & bit) != 0) {
            //Already captured by an earlier flush of this section: the
            //undo target stays the value before the job's FIRST write
            return;
        }
        m_openBits[word] |= bit;

        final int oldPacked = (oldId << 16) | (oldData & 0xFFFF);
        final int newPacked = (newId << 16) | (newData & 0xFFFF);

        if (m_openRunCount > 0 && slotIndex == m_prevSlot + 1
                && oldPacked == m_prevOld && newPacked == m_prevNew) {
            //Extend the current run
            m_openRuns[(m_openRunCount - 1) * INTS_PER_RUN]++;
        } else {
            if (m_openRunCount * INTS_PER_RUN == m_openRuns.length) {
                final int[] grown = new int[m_openRuns.length * 2];
                System.arraycopy(m_openRuns, 0, grown, 0, m_openRuns.length);
                m_openRuns = grown;
            }
            final int base = m_openRunCount * INTS_PER_RUN;
            m_openRuns[base] = (slotIndex << 16) | 1;
            m_openRuns[base + 1] = oldPacked;
            m_openRuns[base + 2] = newPacked;
            m_openRunCount++;
        }

        m_prevSlot = slotIndex;
        m_prevOld = oldPacked;
        m_prevNew = newPacked;
        m_openCaptures++;
    }

    /**
     * Close the open section capture. An empty capture (every slot was
     * already captured) is dropped. Spills closed segments to the spool
     * file once the in-memory bytes exceed the threshold.
     *
     * @throws IOException when the spool file cannot be written
     */
    public void endSection() throws IOException {
        if (m_open == null) {
            throw new IllegalStateException("no section capture is open");
        }
        final Segment segment = m_open;
        m_open = null;

        if (m_openCaptures == 0) {
            m_openRuns = null;
            return;
        }

        segment.runCount = m_openRunCount;
        segment.captureCount = m_openCaptures;
        segment.runs = new int[m_openRunCount * INTS_PER_RUN];
        System.arraycopy(m_openRuns, 0, segment.runs, 0, segment.runs.length);
        m_openRuns = null;

        m_segments.add(segment);
        m_memoryBytes += segment.runs.length * 4L;
        m_captureCount += m_openCaptures;

        if (m_memoryBytes > m_spoolThresholdBytes) {
            spill();
        }
    }

    /**
     * Append every in-memory segment's runs to the spool file and drop
     * the arrays (the directory keeps offset + header)
     */
    private void spill() throws IOException {
        if (m_spoolOut == null) {
            m_spoolOut = new DataOutputStream(new java.io.BufferedOutputStream(
                    new FileOutputStream(m_spoolFile)));
            m_spoolLength = 0;
        }

        for (Segment segment : m_segments) {
            if (segment.runs == null) {
                continue;
            }
            segment.fileOffset = m_spoolLength;
            for (int value : segment.runs) {
                m_spoolOut.writeInt(value);
            }
            m_spoolLength += segment.runs.length * 4L;
            segment.runs = null;
        }
        m_spoolOut.flush();
        m_memoryBytes = 0;
    }

    /**
     * The first-capture bitset of a chunk section (created lazily)
     */
    private long[] bitsOf(int cx, int cz, int section) {
        final Long key = Long.valueOf(SectionMath.chunkKey(cx, cz));
        long[][] sections = m_captured.get(key);
        if (sections == null) {
            sections = new long[SectionMath.SECTIONS_PER_CHUNK][];
            m_captured.put(key, sections);
        }
        long[] bits = sections[section];
        if (bits == null) {
            bits = new long[SectionMath.SECTION_SIZE / 64];
            sections[section] = bits;
        }
        return bits;
    }

    /**
     * Replay every captured change backward (undo order): segments in
     * reverse append order, runs in reverse, slots of a run in reverse.
     *
     * @param visitor the change visitor
     * @throws IOException when a spooled segment cannot be read
     */
    public void replayBackward(IUndoVisitor visitor) throws IOException {
        for (int i = m_segments.size() - 1; i >= 0; i--) {
            final Segment segment = m_segments.get(i);
            final int[] runs = loadRuns(segment);
            for (int r = segment.runCount - 1; r >= 0; r--) {
                emitRun(segment, runs, r, visitor, true);
            }
        }
    }

    /**
     * Replay every captured change forward (redo order)
     *
     * @param visitor the change visitor
     * @throws IOException when a spooled segment cannot be read
     */
    public void replayForward(IUndoVisitor visitor) throws IOException {
        for (int i = 0; i < m_segments.size(); i++) {
            final Segment segment = m_segments.get(i);
            final int[] runs = loadRuns(segment);
            for (int r = 0; r < segment.runCount; r++) {
                emitRun(segment, runs, r, visitor, false);
            }
        }
    }

    private static void emitRun(Segment segment, int[] runs, int r,
            IUndoVisitor visitor, boolean backward) {
        final int base = r * INTS_PER_RUN;
        final int startSlot = runs[base] >>> 16;
        final int count = runs[base] & 0xFFFF;
        final int oldPacked = runs[base + 1];
        final int newPacked = runs[base + 2];
        final int oldId = oldPacked >>> 16;
        final int oldData = oldPacked & 0xFFFF;
        final int newId = newPacked >>> 16;
        final int newData = newPacked & 0xFFFF;

        final int bx = segment.cx << 4;
        final int bz = segment.cz << 4;
        final int by = segment.section << 4;

        for (int k = 0; k < count; k++) {
            final int slot = backward ? startSlot + count - 1 - k : startSlot + k;
            visitor.change(
                    bx + SectionMath.indexToX(slot),
                    by + SectionMath.indexToY(slot),
                    bz + SectionMath.indexToZ(slot),
                    oldId, oldData, newId, newData);
        }
    }

    /**
     * The runs of a segment, from memory or the spool file
     */
    private int[] loadRuns(Segment segment) throws IOException {
        if (segment.runs != null) {
            return segment.runs;
        }

        //Sequential re-read of one segment; replay locality is backward
        //through the file, so a simple per segment read keeps the memory
        //at one segment
        if (m_spoolOut != null) {
            m_spoolOut.flush();
        }
        final int[] runs = new int[segment.runCount * INTS_PER_RUN];
        DataInputStream in = new DataInputStream(new java.io.BufferedInputStream(
                new FileInputStream(m_spoolFile)));
        try {
            long toSkip = segment.fileOffset;
            while (toSkip > 0) {
                final long skipped = in.skip(toSkip);
                if (skipped <= 0) {
                    throw new IOException("cannot seek to segment at " + segment.fileOffset);
                }
                toSkip -= skipped;
            }
            for (int i = 0; i < runs.length; i++) {
                runs[i] = in.readInt();
            }
        } finally {
            in.close();
        }
        return runs;
    }

    /**
     * Number of captured block changes (each slot at most once)
     */
    public long getCaptureCount() {
        return m_captureCount;
    }

    /**
     * Number of stored segments (section flushes with at least one
     * capture)
     */
    public int getSegmentCount() {
        return m_segments.size();
    }

    /**
     * Current unspooled run bytes
     */
    public long getMemoryBytes() {
        return m_memoryBytes;
    }

    /**
     * True when the spool file was created
     */
    public boolean isSpooled() {
        return m_spoolOut != null;
    }

    /**
     * The write-sequence range [firstSeq, lastSeq] of a segment (composite
     * merge key)
     *
     * @param index segment index in append order
     * @return {firstSeq, lastSeq}
     */
    public int[] getSegmentSeqRange(int index) {
        final Segment segment = m_segments.get(index);
        return new int[]{segment.firstSeq, segment.lastSeq};
    }

    /**
     * Release everything and delete the spool file. Idempotent.
     */
    public void close() {
        m_closed = true;
        m_open = null;
        m_openRuns = null;
        m_segments.clear();
        m_captured.clear();
        m_memoryBytes = 0;

        if (m_spoolOut != null) {
            try {
                m_spoolOut.close();
            } catch (IOException ex) {
                //The delete below is the cleanup that matters
            }
            m_spoolOut = null;
        }
        if (m_spoolFile != null && m_spoolFile.exists()) {
            m_spoolFile.delete();
        }
    }
}
