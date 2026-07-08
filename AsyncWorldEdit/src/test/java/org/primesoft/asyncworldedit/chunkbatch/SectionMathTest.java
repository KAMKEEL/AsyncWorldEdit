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
 * Exact value tests for the section index, nibble, chunk key and slot
 * encoding math.
 *
 * @author KAMKEEL
 */
public class SectionMathTest {

    @Test
    public void blockToChunkPositive() {
        assertEquals(0, SectionMath.blockToChunk(0));
        assertEquals(0, SectionMath.blockToChunk(15));
        assertEquals(1, SectionMath.blockToChunk(16));
        assertEquals(2, SectionMath.blockToChunk(32));
    }

    @Test
    public void blockToChunkNegative() {
        assertEquals(-1, SectionMath.blockToChunk(-1));
        assertEquals(-1, SectionMath.blockToChunk(-16));
        assertEquals(-2, SectionMath.blockToChunk(-17));
        assertEquals(-2, SectionMath.blockToChunk(-32));
        assertEquals(-3, SectionMath.blockToChunk(-33));
    }

    @Test
    public void blockToLocalNegative() {
        assertEquals(15, SectionMath.blockToLocal(-1));
        assertEquals(0, SectionMath.blockToLocal(-16));
        assertEquals(15, SectionMath.blockToLocal(-17));
        assertEquals(1, SectionMath.blockToLocal(17));
    }

    @Test
    public void sectionOfY() {
        assertEquals(0, SectionMath.sectionOfY(0));
        assertEquals(0, SectionMath.sectionOfY(15));
        assertEquals(1, SectionMath.sectionOfY(16));
        assertEquals(15, SectionMath.sectionOfY(255));
    }

    @Test
    public void sectionIndexCorners() {
        //index = (y & 15) << 8 | (z & 15) << 4 | (x & 15)
        assertEquals(0, SectionMath.sectionIndex(0, 0, 0));
        assertEquals(15, SectionMath.sectionIndex(15, 0, 0));
        assertEquals(240, SectionMath.sectionIndex(0, 0, 15));
        assertEquals(3840, SectionMath.sectionIndex(0, 15, 0));
        assertEquals(4095, SectionMath.sectionIndex(15, 15, 15));
        assertEquals((5 << 8) | (3 << 4) | 7, SectionMath.sectionIndex(7, 5, 3));
    }

    @Test
    public void sectionIndexWorldCoordinates() {
        //World coordinates are masked to their low 4 bits
        assertEquals(SectionMath.sectionIndex(1, 2, 3),
                SectionMath.sectionIndex(17, 18, 19));
        assertEquals(SectionMath.sectionIndex(15, 15, 15),
                SectionMath.sectionIndex(-1, 255, -1));
        assertEquals(SectionMath.sectionIndex(0, 0, 0),
                SectionMath.sectionIndex(-16, 240, -16));
    }

    @Test
    public void indexRoundTrip() {
        for (int y = 0; y < 16; y += 5) {
            for (int z = 0; z < 16; z += 3) {
                for (int x = 0; x < 16; x += 3) {
                    int index = SectionMath.sectionIndex(x, y, z);
                    assertEquals(x, SectionMath.indexToX(index));
                    assertEquals(y, SectionMath.indexToY(index));
                    assertEquals(z, SectionMath.indexToZ(index));
                }
            }
        }
    }

    @Test
    public void chunkKeyRoundTripNegative() {
        int[][] coords = {{0, 0}, {1, -1}, {-1, 1}, {-1, -1},
        {1875000, -1875000}, {Integer.MIN_VALUE >> 4, Integer.MAX_VALUE >> 4}};

        for (int[] c : coords) {
            long key = SectionMath.chunkKey(c[0], c[1]);
            assertEquals("x of " + c[0] + "," + c[1], c[0], SectionMath.chunkKeyX(key));
            assertEquals("z of " + c[0] + "," + c[1], c[1], SectionMath.chunkKeyZ(key));
        }
    }

    @Test
    public void chunkKeyNoCollision() {
        //(-1, 0) and (0, -1) must not collide (the classic packing bug)
        assertFalse(SectionMath.chunkKey(-1, 0) == SectionMath.chunkKey(0, -1));
        assertFalse(SectionMath.chunkKey(-1, -1) == SectionMath.chunkKey(-2, 0));
    }

    @Test
    public void nibbleSetEvenIndexUsesLowNibble() {
        byte[] data = new byte[2];
        SectionMath.nibbleSet(data, 0, 0xA);
        assertEquals(0x0A, data[0] & 0xFF);
        assertEquals(0, data[1]);
    }

    @Test
    public void nibbleSetOddIndexUsesHighNibble() {
        byte[] data = new byte[2];
        SectionMath.nibbleSet(data, 1, 0xB);
        assertEquals(0xB0, data[0] & 0xFF);
        assertEquals(0, data[1]);
    }

    @Test
    public void nibbleSetKeepsNeighbourNibble() {
        byte[] data = new byte[1];
        SectionMath.nibbleSet(data, 0, 0x3);
        SectionMath.nibbleSet(data, 1, 0xC);
        assertEquals(0xC3, data[0] & 0xFF);

        SectionMath.nibbleSet(data, 0, 0x5);
        assertEquals(0xC5, data[0] & 0xFF);

        SectionMath.nibbleSet(data, 1, 0x1);
        assertEquals(0x15, data[0] & 0xFF);
    }

    @Test
    public void nibbleGetRoundTrip() {
        byte[] data = new byte[SectionMath.NIBBLE_SIZE];
        SectionMath.nibbleSet(data, 4094, 0x7);
        SectionMath.nibbleSet(data, 4095, 0xE);
        assertEquals(0x7, SectionMath.nibbleGet(data, 4094));
        assertEquals(0xE, SectionMath.nibbleGet(data, 4095));
        assertEquals(0xE7, data[2047] & 0xFF);
        assertEquals(0, SectionMath.nibbleGet(data, 0));
    }

    @Test
    public void slotEncodingRoundTrip() {
        int slot = SectionMath.encodeSlot(0, 0, false);
        assertEquals(0, SectionMath.slotId(slot));
        assertEquals(0, SectionMath.slotData(slot));
        assertFalse(SectionMath.slotNotify(slot));
        assertTrue(slot >= 0);

        slot = SectionMath.encodeSlot(32000, 15, true);
        assertEquals(32000, SectionMath.slotId(slot));
        assertEquals(15, SectionMath.slotData(slot));
        assertTrue(SectionMath.slotNotify(slot));

        slot = SectionMath.encodeSlot(0xFFFF, 7, false);
        assertEquals(0xFFFF, SectionMath.slotId(slot));
        assertEquals(7, SectionMath.slotData(slot));
        assertFalse(SectionMath.slotNotify(slot));
        assertTrue("slot value must stay positive (EMPTY_SLOT is -1)", slot >= 0);
    }
}
