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

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.primesoft.asyncworldedit.chunkbatch.SectionMath;

/**
 * Exact-value tests for the columnar undo delta stream: RLE encode/decode
 * incl. run boundaries and non-runs, the first-capture bitset across
 * multiple flushes of the same section, backward/forward replay order,
 * spool threshold crossing + reload from file, NEID-wide values and the
 * spool file lifecycle.
 *
 * @author KAMKEEL
 */
public class ColumnarUndoLogTest {

    /**
     * Recording visitor: "x,y,z:oldId:oldData>newId:newData"
     */
    private static final class Recorder implements ColumnarUndoLog.IUndoVisitor {

        final List<String> changes = new ArrayList<String>();

        @Override
        public void change(int x, int y, int z, int oldId, int oldData,
                int newId, int newData) {
            changes.add(x + "," + y + "," + z + ":" + oldId + ":" + oldData
                    + ">" + newId + ":" + newData);
        }
    }

    private File m_spool;
    private ColumnarUndoLog m_log;

    @Before
    public void createSpoolFile() throws Exception {
        m_spool = File.createTempFile("awe-undo-test", ".bin");
        //The log creates it lazily on the first spill
        m_spool.delete();
    }

    @After
    public void cleanup() {
        if (m_log != null) {
            m_log.close();
        }
        if (m_spool != null && m_spool.exists()) {
            m_spool.delete();
        }
    }

    private ColumnarUndoLog log(long thresholdBytes) {
        m_log = new ColumnarUndoLog(m_spool, thresholdBytes);
        return m_log;
    }

    @Test
    public void uniformSectionCompressesToOneRun() throws Exception {
        ColumnarUndoLog log = log(Long.MAX_VALUE);

        //A full uniform section: 4096 slots, same old/new pair everywhere
        log.beginSection(0, 0, 4, 0, 4095);
        for (int slot = 0; slot < SectionMath.SECTION_SIZE; slot++) {
            log.capture(slot, 1, 0, 98, 3);
        }
        log.endSection();

        assertEquals(4096, log.getCaptureCount());
        assertEquals(1, log.getSegmentCount());
        //One run = 3 ints = 12 bytes for the whole section
        assertEquals(12, log.getMemoryBytes());

        Recorder recorder = new Recorder();
        log.replayForward(recorder);
        assertEquals(4096, recorder.changes.size());
        //Slot 0 of section 4 in chunk (0,0) is world (0, 64, 0)
        assertEquals("0,64,0:1:0>98:3", recorder.changes.get(0));
    }

    @Test
    public void runBoundariesSplitOnValueChangeAndSlotGap() throws Exception {
        ColumnarUndoLog log = log(Long.MAX_VALUE);

        log.beginSection(0, 0, 0, 0, 5);
        log.capture(0, 1, 0, 5, 0);   //run 1
        log.capture(1, 1, 0, 5, 0);   //extends run 1
        log.capture(2, 2, 0, 5, 0);   //new run (old value changed)
        log.capture(4, 2, 0, 5, 0);   //new run (slot gap)
        log.capture(5, 2, 0, 5, 1);   //new run (new value changed)
        log.endSection();

        assertEquals(5, log.getCaptureCount());
        //4 runs * 12 bytes
        assertEquals(48, log.getMemoryBytes());

        Recorder forward = new Recorder();
        log.replayForward(forward);
        assertEquals(5, forward.changes.size());
        assertEquals("0,0,0:1:0>5:0", forward.changes.get(0));
        assertEquals("1,0,0:1:0>5:0", forward.changes.get(1));
        assertEquals("2,0,0:2:0>5:0", forward.changes.get(2));
        assertEquals("4,0,0:2:0>5:0", forward.changes.get(3));
        assertEquals("5,0,0:2:0>5:1", forward.changes.get(4));
    }

    @Test
    public void firstCaptureWinsAcrossMultipleFlushesOfTheSameSection() throws Exception {
        ColumnarUndoLog log = log(Long.MAX_VALUE);

        //First flush of section (0,0,0) captures slot 7 with old value 1
        log.beginSection(0, 0, 0, 0, 10);
        log.capture(7, 1, 0, 20, 0);
        log.endSection();

        //The chunk was streamed out, rewritten and flushes again: slot 7
        //now reads old value 20 (the job's own earlier write) - the
        //re-capture must be rejected, slot 8 is new and captured
        log.beginSection(0, 0, 0, 11, 20);
        log.capture(7, 20, 0, 30, 0);
        log.capture(8, 1, 0, 30, 0);
        log.endSection();

        assertEquals(2, log.getCaptureCount());
        //Two normal segments plus the redo-only rewrite segment of slot 7
        assertEquals(3, log.getSegmentCount());

        Recorder recorder = new Recorder();
        log.replayBackward(recorder);
        assertEquals(2, recorder.changes.size());
        //Backward: second segment first (slot 8), then slot 7 with the
        //ORIGINAL old value
        assertEquals("8,0,0:1:0>30:0", recorder.changes.get(0));
        assertEquals("7,0,0:1:0>20:0", recorder.changes.get(1));
    }

    @Test
    public void allRecaptureFlushYieldsOnlyARedoSegment() throws Exception {
        ColumnarUndoLog log = log(Long.MAX_VALUE);

        log.beginSection(0, 0, 0, 0, 1);
        log.capture(0, 1, 0, 2, 0);
        log.endSection();

        //Every slot of the second flush was already captured: no normal
        //segment, but the rewrite lands in a redo-only segment so redo
        //ends at the final value
        log.beginSection(0, 0, 0, 2, 3);
        log.capture(0, 2, 0, 3, 0);
        log.endSection();

        assertEquals(2, log.getSegmentCount());
        assertFalse(log.isSegmentRedoOnly(0));
        assertTrue(log.isSegmentRedoOnly(1));
        //The undo size counts first captures only
        assertEquals(1, log.getCaptureCount());
    }

    @Test
    public void crossFlushRewriteRedoEndsAtFinalValueUndoAtOriginal() throws Exception {
        ColumnarUndoLog log = log(Long.MAX_VALUE);

        //Flush 1: slot 7 captured with the world value 1, job writes 20
        log.beginSection(0, 0, 0, 0, 10);
        log.capture(7, 1, 0, 20, 0);
        log.endSection();

        //The chunk was streamed out and rewritten (//move overlap): the
        //re-flush reads the job's own 20 as "old" and writes 30; slot 8
        //is fresh
        log.beginSection(0, 0, 0, 11, 20);
        log.capture(7, 20, 0, 30, 0);
        log.capture(8, 1, 0, 30, 0);
        log.endSection();

        //Undo: only the first captures, slot 7 restores the ORIGINAL 1
        Recorder backward = new Recorder();
        log.replayBackward(backward);
        assertEquals(2, backward.changes.size());
        assertEquals("8,0,0:1:0>30:0", backward.changes.get(0));
        assertEquals("7,0,0:1:0>20:0", backward.changes.get(1));

        //Redo: forward replay ends at the FINAL value for slot 7 (30, via
        //the redo-only rewrite segment appended after the normal segment)
        Recorder forward = new Recorder();
        log.replayForward(forward);
        assertEquals(3, forward.changes.size());
        assertEquals("7,0,0:1:0>20:0", forward.changes.get(0));
        assertEquals("8,0,0:1:0>30:0", forward.changes.get(1));
        assertEquals("7,0,0:20:0>30:0", forward.changes.get(2));
    }

    @Test
    public void crossFlushRewriteSurvivesSpooling() throws Exception {
        //Threshold 0: every segment (normal and redo-only) spills to disk
        ColumnarUndoLog log = log(0);

        log.beginSection(0, 0, 0, 0, 10);
        log.capture(7, 1, 0, 20, 0);
        log.endSection();
        log.beginSection(0, 0, 0, 11, 20);
        log.capture(7, 20, 0, 30, 0);
        log.capture(8, 1, 0, 30, 0);
        log.endSection();

        assertTrue(log.isSpooled());
        assertEquals(0, log.getMemoryBytes());

        Recorder backward = new Recorder();
        log.replayBackward(backward);
        assertEquals(2, backward.changes.size());
        assertEquals("8,0,0:1:0>30:0", backward.changes.get(0));
        assertEquals("7,0,0:1:0>20:0", backward.changes.get(1));

        Recorder forward = new Recorder();
        log.replayForward(forward);
        assertEquals(3, forward.changes.size());
        assertEquals("7,0,0:20:0>30:0", forward.changes.get(2));
    }

    @Test
    public void tripleRewriteRedoReplaysEveryIntermediateInOrder() throws Exception {
        ColumnarUndoLog log = log(Long.MAX_VALUE);

        log.beginSection(0, 0, 0, 0, 1);
        log.capture(0, 1, 0, 10, 0);
        log.endSection();
        log.beginSection(0, 0, 0, 2, 3);
        log.capture(0, 10, 0, 11, 0);
        log.endSection();
        log.beginSection(0, 0, 0, 4, 5);
        log.capture(0, 11, 0, 12, 0);
        log.endSection();

        //Undo: exactly one change, the original old value
        Recorder backward = new Recorder();
        log.replayBackward(backward);
        assertEquals(1, backward.changes.size());
        assertEquals("0,0,0:1:0>10:0", backward.changes.get(0));

        //Redo: append order, the LAST value wins
        Recorder forward = new Recorder();
        log.replayForward(forward);
        assertEquals(3, forward.changes.size());
        assertEquals("0,0,0:1:0>10:0", forward.changes.get(0));
        assertEquals("0,0,0:10:0>11:0", forward.changes.get(1));
        assertEquals("0,0,0:11:0>12:0", forward.changes.get(2));
    }

    @Test
    public void rewriteAndFreshSlotsInterleaveInOneFlushBothOrders() throws Exception {
        //Interleaving A: the re-captured slot comes BEFORE the fresh slot
        ColumnarUndoLog logA = log(Long.MAX_VALUE);
        logA.beginSection(0, 0, 0, 0, 1);
        logA.capture(3, 1, 0, 20, 0);
        logA.endSection();
        logA.beginSection(0, 0, 0, 2, 3);
        logA.capture(3, 20, 0, 30, 0);  //re-capture (lower slot)
        logA.capture(9, 1, 0, 30, 0);   //fresh (higher slot)
        logA.endSection();

        Recorder backwardA = new Recorder();
        logA.replayBackward(backwardA);
        assertEquals(2, backwardA.changes.size());
        assertEquals("9,0,0:1:0>30:0", backwardA.changes.get(0));
        assertEquals("3,0,0:1:0>20:0", backwardA.changes.get(1));

        Recorder forwardA = new Recorder();
        logA.replayForward(forwardA);
        assertEquals(3, forwardA.changes.size());
        assertEquals("3,0,0:1:0>20:0", forwardA.changes.get(0));
        assertEquals("9,0,0:1:0>30:0", forwardA.changes.get(1));
        assertEquals("3,0,0:20:0>30:0", forwardA.changes.get(2));
        logA.close();

        //Interleaving B: the fresh slot comes BEFORE the re-captured slot
        ColumnarUndoLog logB = log(Long.MAX_VALUE);
        logB.beginSection(0, 0, 0, 0, 1);
        logB.capture(9, 1, 0, 20, 0);
        logB.endSection();
        logB.beginSection(0, 0, 0, 2, 3);
        logB.capture(3, 1, 0, 30, 0);   //fresh (lower slot)
        logB.capture(9, 20, 0, 30, 0);  //re-capture (higher slot)
        logB.endSection();

        Recorder backwardB = new Recorder();
        logB.replayBackward(backwardB);
        assertEquals(2, backwardB.changes.size());
        assertEquals("3,0,0:1:0>30:0", backwardB.changes.get(0));
        assertEquals("9,0,0:1:0>20:0", backwardB.changes.get(1));

        Recorder forwardB = new Recorder();
        logB.replayForward(forwardB);
        assertEquals(3, forwardB.changes.size());
        assertEquals("9,0,0:1:0>20:0", forwardB.changes.get(0));
        assertEquals("3,0,0:1:0>30:0", forwardB.changes.get(1));
        assertEquals("9,0,0:20:0>30:0", forwardB.changes.get(2));
    }

    @Test
    public void backwardIsTheExactMirrorOfForward() throws Exception {
        ColumnarUndoLog log = log(Long.MAX_VALUE);

        log.beginSection(0, 0, 0, 0, 2);
        log.capture(0, 1, 0, 9, 0);
        log.capture(1, 1, 0, 9, 0);
        log.endSection();
        log.beginSection(1, 0, 0, 3, 4);
        log.capture(0, 2, 0, 9, 0);
        log.endSection();

        Recorder forward = new Recorder();
        log.replayForward(forward);
        Recorder backward = new Recorder();
        log.replayBackward(backward);

        assertEquals(forward.changes.size(), backward.changes.size());
        for (int i = 0; i < forward.changes.size(); i++) {
            assertEquals(forward.changes.get(i),
                    backward.changes.get(backward.changes.size() - 1 - i));
        }
        //Backward starts with the LAST captured block (chunk 1)
        assertEquals("16,0,0:2:0>9:0", backward.changes.get(0));
    }

    @Test
    public void spoolThresholdCrossingWritesToDiskAndReplaysExactly() throws Exception {
        //Threshold 0: every endSection spills to the file
        ColumnarUndoLog log = log(0);

        log.beginSection(0, 0, 0, 0, 1);
        log.capture(0, 1, 0, 7, 0);
        log.capture(1, 1, 0, 7, 0);
        log.endSection();
        log.beginSection(0, 0, 1, 2, 3);
        log.capture(0, 3, 2, 7, 0);
        log.endSection();

        assertTrue("the spool file must exist after the spill", m_spool.exists());
        assertTrue(log.isSpooled());
        assertEquals("everything spilled", 0, log.getMemoryBytes());
        assertEquals(2, log.getSegmentCount());

        //Replay reloads the segments from the file, exact values
        Recorder backward = new Recorder();
        log.replayBackward(backward);
        assertEquals(3, backward.changes.size());
        //Section 1 slot 0 = world y 16
        assertEquals("0,16,0:3:2>7:0", backward.changes.get(0));
        assertEquals("1,0,0:1:0>7:0", backward.changes.get(1));
        assertEquals("0,0,0:1:0>7:0", backward.changes.get(2));

        //Mixed memory + file: a third segment stays in memory only until
        //the next threshold check spills it (threshold 0 spills at once)
        log.beginSection(2, 2, 2, 4, 5);
        log.capture(9, 4, 0, 7, 0);
        log.endSection();
        Recorder forward = new Recorder();
        log.replayForward(forward);
        assertEquals(4, forward.changes.size());
    }

    @Test
    public void closeDeletesTheSpoolFileAndRefusesFurtherCaptures() throws Exception {
        ColumnarUndoLog log = log(0);

        log.beginSection(0, 0, 0, 0, 0);
        log.capture(0, 1, 0, 2, 0);
        log.endSection();
        assertTrue(m_spool.exists());

        log.close();
        assertFalse("close() must delete the spool file", m_spool.exists());
        assertEquals(0, log.getSegmentCount());

        try {
            log.beginSection(0, 0, 0, 0, 0);
            fail("a closed log must refuse captures");
        } catch (IllegalStateException ex) {
            //Expected
        }
        //close is idempotent
        log.close();
    }

    @Test
    public void neidWideIdsAndWideMetadataSurviveTheRoundtrip() throws Exception {
        ColumnarUndoLog log = log(0);

        //NEID: ids above 4095 and metadata above 15 (16 bit meta layout)
        log.beginSection(-2, 3, 15, 0, 0);
        log.capture(4095, 40000, 12345, 65535, 9999);
        log.endSection();

        Recorder recorder = new Recorder();
        log.replayBackward(recorder);
        assertEquals(1, recorder.changes.size());
        //Chunk (-2,3), section 15, slot 4095 = local (15,15,15) =>
        //world (-32+15, 240+15, 48+15)
        assertEquals("-17,255,63:40000:12345>65535:9999", recorder.changes.get(0));
    }

    @Test
    public void segmentCursorExposesMetaAndRunsFromMemoryAndFile() throws Exception {
        //Threshold 0: every endSection spills, so the cursor reads the
        //runs back from the spool file with exact values
        ColumnarUndoLog log = log(0);

        log.beginSection(3, -1, 2, 10, 12);
        log.capture(5, 1, 2, 3, 4);   //run 1
        log.capture(6, 1, 2, 3, 4);   //extends run 1
        log.capture(9, 7, 0, 3, 4);   //run 2 (slot gap)
        log.endSection();

        assertEquals(3, log.getSegmentChunkX(0));
        assertEquals(-1, log.getSegmentChunkZ(0));
        assertEquals(2, log.getSegmentSection(0));
        assertEquals(2, log.getSegmentRunCount(0));
        assertEquals(3, log.getSegmentCaptureCount(0));

        final int[] runs = log.loadSegmentRuns(0);
        assertEquals(ColumnarUndoLog.INTS_PER_RUN * 2, runs.length);
        assertEquals(5, ColumnarUndoLog.runStartSlot(runs, 0));
        assertEquals(2, ColumnarUndoLog.runLength(runs, 0));
        assertEquals(1, ColumnarUndoLog.runOldId(runs, 0));
        assertEquals(2, ColumnarUndoLog.runOldData(runs, 0));
        assertEquals(3, ColumnarUndoLog.runNewId(runs, 0));
        assertEquals(4, ColumnarUndoLog.runNewData(runs, 0));
        assertEquals(9, ColumnarUndoLog.runStartSlot(runs, 1));
        assertEquals(1, ColumnarUndoLog.runLength(runs, 1));
        assertEquals(7, ColumnarUndoLog.runOldId(runs, 1));
    }

    @Test
    public void segmentSeqRangesArePreservedForTheCompositeMerge() throws Exception {
        ColumnarUndoLog log = log(Long.MAX_VALUE);

        log.beginSection(0, 0, 0, 100, 250);
        log.capture(0, 1, 0, 2, 0);
        log.endSection();
        log.beginSection(0, 0, 1, 251, 400);
        log.capture(0, 1, 0, 2, 0);
        log.endSection();

        assertArrayEquals(new int[]{100, 250}, log.getSegmentSeqRange(0));
        assertArrayEquals(new int[]{251, 400}, log.getSegmentSeqRange(1));
    }
}
