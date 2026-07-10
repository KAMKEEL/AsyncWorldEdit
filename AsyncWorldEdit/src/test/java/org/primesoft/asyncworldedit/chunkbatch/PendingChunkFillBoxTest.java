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

import static org.junit.Assert.*;
import org.junit.Test;

/**
 * Exact-value tests for the fast lane's bulk fill: geometry, counters,
 * new-section accounting and the interplay with per block writes.
 *
 * @author KAMKEEL
 */
public class PendingChunkFillBoxTest {

    @Test
    public void fullSectionFillStoresEverySlot() {
        PendingChunk chunk = new PendingChunk(0, 0);
        int added = chunk.fillBox(0, 16, 0, 15, 31, 15, 42, 3, false, 7);

        assertEquals(4096, added);
        assertEquals(4096, chunk.getCount());
        assertEquals(1, chunk.getSectionCount());
        assertEquals(7, chunk.getLastWriteSeq());

        int slot = chunk.getPendingSlot(5, 20, 9);
        assertEquals(42, SectionMath.slotId(slot));
        assertEquals(3, SectionMath.slotData(slot));
        //Outside the box: nothing pending
        assertEquals(SectionMath.EMPTY_SLOT, chunk.getPendingSlot(5, 15, 9));
        assertEquals(SectionMath.EMPTY_SLOT, chunk.getPendingSlot(5, 32, 9));
    }

    @Test
    public void crossSectionBoxSplitsExactlyAtSectionBounds() {
        PendingChunk chunk = new PendingChunk(2, -3);
        //Chunk (2,-3): world x 32..47, z -48..-33. Box spans sections 0..2
        int added = chunk.fillBox(34, 10, -40, 36, 40, -38, 1, 0, false, 1);

        //3 x 31 x 3 = 279 slots
        assertEquals(279, added);
        assertEquals(279, chunk.getCount());
        assertEquals(3, chunk.getSectionCount());

        assertEquals(1, SectionMath.slotId(chunk.getPendingSlot(34, 10, -40)));
        assertEquals(1, SectionMath.slotId(chunk.getPendingSlot(36, 40, -38)));
        assertEquals(SectionMath.EMPTY_SLOT, chunk.getPendingSlot(34, 9, -40));
        assertEquals(SectionMath.EMPTY_SLOT, chunk.getPendingSlot(34, 41, -40));
        assertEquals(SectionMath.EMPTY_SLOT, chunk.getPendingSlot(33, 10, -40));
        assertEquals(SectionMath.EMPTY_SLOT, chunk.getPendingSlot(37, 10, -38));
    }

    @Test
    public void fillOverExistingWritesDoesNotDoubleCount() {
        PendingChunk chunk = new PendingChunk(0, 0);
        assertTrue(chunk.setBlock(3, 5, 3, 99, 1, false, 1));
        assertEquals(1, chunk.getCount());

        //Fill covering the existing write: only the other slots are new
        int added = chunk.fillBox(2, 4, 2, 4, 6, 4, 7, 0, false, 2);
        assertEquals(26, added);
        assertEquals(27, chunk.getCount());

        //Last write wins at the overlapping slot
        int slot = chunk.getPendingSlot(3, 5, 3);
        assertEquals(7, SectionMath.slotId(slot));
        assertEquals(0, SectionMath.slotData(slot));
    }

    @Test
    public void newSectionsInYRangeCountsOnlyMissing() {
        PendingChunk chunk = new PendingChunk(0, 0);
        assertEquals(2, chunk.newSectionsInYRange(0, 31));

        chunk.setBlock(0, 5, 0, 1, 0, false, 1);
        assertEquals(1, chunk.newSectionsInYRange(0, 31));
        assertEquals(0, chunk.newSectionsInYRange(0, 15));
        assertEquals(15, chunk.newSectionsInYRange(0, 255));
        assertEquals(0, chunk.newSectionsInYRange(-1, 5));
        assertEquals(0, chunk.newSectionsInYRange(5, 300));
    }

    @Test
    public void fillOutsideChunkOrInvalidBoxStoresNothing() {
        PendingChunk chunk = new PendingChunk(0, 0);
        //x range leaves the chunk
        assertEquals(0, chunk.fillBox(10, 0, 0, 17, 5, 5, 1, 0, false, 1));
        //inverted y
        assertEquals(0, chunk.fillBox(0, 10, 0, 5, 5, 5, 1, 0, false, 1));
        //y out of world
        assertEquals(0, chunk.fillBox(0, -1, 0, 5, 5, 5, 1, 0, false, 1));
        assertEquals(0, chunk.getCount());
        assertEquals(0, chunk.getSectionCount());
    }

    @Test
    public void filledSlotsReplayWithTheFillSequence() {
        PendingChunk chunk = new PendingChunk(0, 0);
        chunk.fillBox(0, 0, 0, 1, 0, 1, 5, 2, false, 9);

        final int[] visited = {0};
        chunk.forEachLastWriteOrder(new PendingChunk.IPendingBlockVisitor() {
            @Override
            public void visit(int x, int y, int z, int id, int data,
                    boolean notify, int seq) {
                assertEquals(5, id);
                assertEquals(2, data);
                assertEquals(9, seq);
                assertFalse(notify);
                visited[0]++;
            }
        });
        assertEquals(4, visited[0]);
    }
}
