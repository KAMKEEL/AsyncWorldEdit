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

import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;
import org.junit.Test;
import org.primesoft.asyncworldedit.chunkbatch.PendingChunk;
import org.primesoft.asyncworldedit.chunkbatch.SectionMath;

/**
 * Exact-value tests for the flush-time capture walk: one begin/capture/end
 * bracket per pending section with the scanned sequence range, ascending
 * slot order, the pre-write old values from the injected reader and the
 * miss counting for unreadable slots.
 *
 * @author KAMKEEL
 */
public class ChunkCaptureUtilTest {

    /**
     * Recording sink: "begin cx,cz,s:firstSeq-lastSeq", "cap
     * slot:oldId:oldData&gt;newId:newData", "end"
     */
    private static final class RecordingSink implements ICaptureSink {

        final List<String> events = new ArrayList<String>();

        @Override
        public void beginSection(int cx, int cz, int section, int firstSeq, int lastSeq) {
            events.add("begin " + cx + "," + cz + "," + section
                    + ":" + firstSeq + "-" + lastSeq);
        }

        @Override
        public void capture(int slotIndex, int oldId, int oldData, int newId, int newData) {
            events.add("cap " + slotIndex + ":" + oldId + ":" + oldData
                    + ">" + newId + ":" + newData);
        }

        @Override
        public void endSection() {
            events.add("end");
        }

        @Override
        public void captureCleared(int x, int y, int z, int oldId, int oldData,
                int newId, int newData, int seq) {
            fail("the chunk capture walk must never emit cleared captures");
        }

        @Override
        public void jobDone() {
            fail("the chunk capture walk must never end a job");
        }
    }

    @Test
    public void pendingSectionsAreCapturedInBracketsWithSeqRangeAndAscendingSlots() {
        //Chunk (1,-1): world base (16, -16)
        final PendingChunk chunk = new PendingChunk(1, -1);
        chunk.setBlock(17, 5, -14, 10, 2, true, 100);
        chunk.setBlock(18, 5, -14, 10, 2, true, 101);
        chunk.setBlock(16, 70, -16, 20, 0, true, 99);

        final RecordingSink sink = new RecordingSink();
        //Old value: constant (1, 1) everywhere
        final int misses = ChunkCaptureUtil.capture(chunk, sink, new ISlotReader() {
            @Override
            public int read(int x, int y, int z) {
                return SectionMath.encodeSlot(1, 1, false);
            }
        });

        assertEquals(0, misses);

        //Section 0 first (ascending section order), slots ascending:
        //(lx=1, y=5, lz=2) -> 1313, (lx=2, y=5, lz=2) -> 1314; then
        //section 4 with (lx=0, y=70, lz=0) -> 1536
        final int slotA = SectionMath.sectionIndex(1, 5, 2);
        final int slotB = SectionMath.sectionIndex(2, 5, 2);
        final int slotC = SectionMath.sectionIndex(0, 70, 0);
        assertTrue(slotA < slotB);

        assertEquals(7, sink.events.size());
        assertEquals("begin 1,-1,0:100-101", sink.events.get(0));
        assertEquals("cap " + slotA + ":1:1>10:2", sink.events.get(1));
        assertEquals("cap " + slotB + ":1:1>10:2", sink.events.get(2));
        assertEquals("end", sink.events.get(3));
        assertEquals("begin 1,-1,4:99-99", sink.events.get(4));
        assertEquals("cap " + slotC + ":1:1>20:0", sink.events.get(5));
        assertEquals("end", sink.events.get(6));
    }

    @Test
    public void unreadableSlotsAreSkippedAndCounted() {
        final PendingChunk chunk = new PendingChunk(0, 0);
        chunk.setBlock(0, 0, 0, 5, 0, true, 1);
        chunk.setBlock(1, 0, 0, 5, 0, true, 2);

        final RecordingSink sink = new RecordingSink();
        final int misses = ChunkCaptureUtil.capture(chunk, sink, new ISlotReader() {
            @Override
            public int read(int x, int y, int z) {
                //(0,0,0) cannot be read; (1,0,0) reads (2,0)
                return x == 0 ? SectionMath.EMPTY_SLOT
                        : SectionMath.encodeSlot(2, 0, false);
            }
        });

        assertEquals(1, misses);
        assertEquals(3, sink.events.size());
        assertEquals("begin 0,0,0:1-2", sink.events.get(0));
        assertEquals("cap " + SectionMath.sectionIndex(1, 0, 0) + ":2:0>5:0",
                sink.events.get(1));
        assertEquals("end", sink.events.get(2));
    }

    @Test
    public void emptyChunkEmitsNothing() {
        final RecordingSink sink = new RecordingSink();
        final int misses = ChunkCaptureUtil.capture(new PendingChunk(3, 3), sink,
                new ISlotReader() {
            @Override
            public int read(int x, int y, int z) {
                return SectionMath.encodeSlot(0, 0, false);
            }
        });

        assertEquals(0, misses);
        assertTrue(sink.events.isEmpty());
    }
}
