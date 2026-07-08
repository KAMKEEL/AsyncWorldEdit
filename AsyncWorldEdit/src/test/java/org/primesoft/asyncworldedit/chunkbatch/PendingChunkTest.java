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
 * Tests for the per chunk pending block container, including negative
 * world coordinates and chunk edges.
 *
 * @author KAMKEEL
 */
public class PendingChunkTest {

    @Test
    public void acceptsOnlyOwnCoordinates() {
        PendingChunk chunk = new PendingChunk(0, 0);

        assertTrue(chunk.setBlock(0, 0, 0, 1, 0, false));
        assertTrue(chunk.setBlock(15, 255, 15, 1, 0, false));
        //x = 16 is the next chunk
        assertFalse(chunk.setBlock(16, 0, 0, 1, 0, false));
        //x = -1 is the previous chunk
        assertFalse(chunk.setBlock(-1, 0, 0, 1, 0, false));
        assertFalse(chunk.setBlock(0, 0, 16, 1, 0, false));
        //y out of range
        assertFalse(chunk.setBlock(0, -1, 0, 1, 0, false));
        assertFalse(chunk.setBlock(0, 256, 0, 1, 0, false));

        assertEquals(2, chunk.getCount());
    }

    @Test
    public void negativeChunkCoordinates() {
        PendingChunk chunk = new PendingChunk(-1, -1);

        //Block (-1, 0, -1) is local (15, 15) in chunk (-1, -1)
        assertTrue(chunk.setBlock(-1, 0, -1, 42, 3, false));
        //Block (-16, 0, -16) is local (0, 0) in chunk (-1, -1)
        assertTrue(chunk.setBlock(-16, 0, -16, 43, 0, false));
        //Block (-17, ...) belongs to chunk (-2, ...)
        assertFalse(chunk.setBlock(-17, 0, -1, 44, 0, false));

        int slot = chunk.getPendingSlot(-1, 0, -1);
        assertEquals(42, SectionMath.slotId(slot));
        assertEquals(3, SectionMath.slotData(slot));

        assertEquals(slot, chunk.getPendingSlotLocal(15, 0, 15));
        assertEquals(43, SectionMath.slotId(chunk.getPendingSlotLocal(0, 0, 0)));
    }

    @Test
    public void sectionsSplitByY() {
        PendingChunk chunk = new PendingChunk(2, 3);
        int bx = 2 << 4;
        int bz = 3 << 4;

        assertTrue(chunk.setBlock(bx, 0, bz, 1, 0, false));
        assertTrue(chunk.setBlock(bx, 15, bz, 2, 0, false));
        assertTrue(chunk.setBlock(bx, 16, bz, 3, 0, false));
        assertTrue(chunk.setBlock(bx, 255, bz, 4, 0, false));

        assertNotNull(chunk.getSection(0));
        assertNotNull(chunk.getSection(1));
        assertNotNull(chunk.getSection(15));
        assertNull(chunk.getSection(2));

        assertEquals(2, chunk.getSection(0).getCount());
        assertEquals(1, chunk.getSection(1).getCount());
        assertEquals(1, chunk.getSection(15).getCount());
        assertEquals(4, chunk.getCount());

        assertEquals(2, SectionMath.slotId(chunk.getPendingSlot(bx, 15, bz)));
        assertEquals(3, SectionMath.slotId(chunk.getPendingSlot(bx, 16, bz)));
        assertEquals(4, SectionMath.slotId(chunk.getPendingSlot(bx, 255, bz)));
    }

    @Test
    public void lastWriteWinsKeepsCount() {
        PendingChunk chunk = new PendingChunk(0, 0);
        assertTrue(chunk.setBlock(5, 70, 5, 1, 0, false));
        assertTrue(chunk.setBlock(5, 70, 5, 20, 7, true));

        assertEquals(1, chunk.getCount());
        int slot = chunk.getPendingSlot(5, 70, 5);
        assertEquals(20, SectionMath.slotId(slot));
        assertEquals(7, SectionMath.slotData(slot));
        assertTrue(SectionMath.slotNotify(slot));
    }

    @Test
    public void pendingSlotEmptyForUnknownPositions() {
        PendingChunk chunk = new PendingChunk(0, 0);
        chunk.setBlock(1, 64, 1, 1, 0, false);

        assertEquals(SectionMath.EMPTY_SLOT, chunk.getPendingSlot(2, 64, 1));
        assertEquals(SectionMath.EMPTY_SLOT, chunk.getPendingSlot(1, 65, 1));
        //Outside of this chunk
        assertEquals(SectionMath.EMPTY_SLOT, chunk.getPendingSlot(17, 64, 1));
        assertEquals(SectionMath.EMPTY_SLOT, chunk.getPendingSlot(1, -1, 1));
        assertEquals(SectionMath.EMPTY_SLOT, chunk.getPendingSlot(1, 256, 1));
        assertEquals(SectionMath.EMPTY_SLOT, chunk.getPendingSlotLocal(16, 64, 0));
        assertEquals(SectionMath.EMPTY_SLOT, chunk.getPendingSlotLocal(-1, 64, 0));
    }
}
