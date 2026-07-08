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

import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;
import org.junit.Test;

/**
 * Exact value tests for the pending section buffer and the vanilla /
 * NotEnoughIDs array packing.
 *
 * @author KAMKEEL
 */
public class PendingSectionTest {

    private static class ChangeCollector implements PendingSection.IChangedBlockVisitor {

        final List<int[]> changes = new ArrayList<int[]>();

        @Override
        public void changed(int index, int oldId, int newId) {
            changes.add(new int[]{index, oldId, newId});
        }
    }

    private static class OverflowCollector implements PendingSection.IOverflowVisitor {

        final List<int[]> blocks = new ArrayList<int[]>();

        @Override
        public void overflow(int index, int id, int data, boolean notify) {
            blocks.add(new int[]{index, id, data, notify ? 1 : 0});
        }
    }

    @Test
    public void countAndLastWriteWins() {
        PendingSection section = new PendingSection();
        assertEquals(0, section.getCount());
        assertEquals(SectionMath.EMPTY_SLOT, section.getSlot(100));

        section.set(100, 1, 0, false);
        assertEquals(1, section.getCount());
        assertEquals(1, section.getNonAirCount());

        //Same position again - count stays, value replaced
        section.set(100, 35, 14, true);
        assertEquals(1, section.getCount());
        assertEquals(1, section.getNonAirCount());

        int slot = section.getSlot(100);
        assertEquals(35, SectionMath.slotId(slot));
        assertEquals(14, SectionMath.slotData(slot));
        assertTrue(SectionMath.slotNotify(slot));

        //Overwrite with air
        section.set(100, 0, 0, false);
        assertEquals(1, section.getCount());
        assertEquals(0, section.getNonAirCount());

        section.set(101, 0, 0, false);
        assertEquals(2, section.getCount());
        assertEquals(0, section.getNonAirCount());

        section.set(101, 5, 0, false);
        assertEquals(1, section.getNonAirCount());
    }

    @Test
    public void needsMsb() {
        PendingSection section = new PendingSection();
        assertFalse(section.needsMsb());

        section.set(0, 255, 0, false);
        assertFalse(section.needsMsb());

        section.set(1, 256, 0, false);
        assertTrue(section.needsMsb());

        //Ids over the vanilla 12 bit range never touch the MSB array
        PendingSection big = new PendingSection();
        big.set(0, 4096, 0, false);
        assertFalse(big.needsMsb());
    }

    @Test
    public void applyVanillaExactBytes() {
        PendingSection section = new PendingSection();
        byte[] lsb = new byte[SectionMath.SECTION_SIZE];
        byte[] msb = new byte[SectionMath.NIBBLE_SIZE];
        byte[] meta = new byte[SectionMath.NIBBLE_SIZE];

        //even index: id 300 = 0x12C -> LSB 0x2C, MSB nibble 1
        section.set(0, 300, 5, false);
        //odd index: id 4095 -> LSB 0xFF, MSB nibble 0xF
        section.set(1, 4095, 15, false);
        //id fits one byte, no MSB
        section.set(2, 89, 0, false);

        int written = section.applyVanilla(lsb, msb, meta, null, null);
        assertEquals(3, written);

        assertEquals(0x2C, lsb[0] & 0xFF);
        assertEquals(0xFF, lsb[1] & 0xFF);
        assertEquals(89, lsb[2] & 0xFF);

        //msb byte 0: index 0 low nibble = 1, index 1 high nibble = 0xF
        assertEquals(0xF1, msb[0] & 0xFF);
        //msb byte 1: index 2 low nibble = 0
        assertEquals(0x00, msb[1] & 0xFF);

        //meta byte 0: index 0 low nibble = 5, index 1 high nibble = 0xF
        assertEquals(0xF5, meta[0] & 0xFF);
        assertEquals(0x00, meta[1] & 0xFF);
    }

    @Test
    public void applyVanillaClearsStaleMsbAndMeta() {
        //The section already contains id 300:5 (MSB nibble set); writing a
        //low id over it must clear the MSB nibble and the metadata,
        //otherwise the id would be corrupted to 256 + newId
        PendingSection prepare = new PendingSection();
        byte[] lsb = new byte[SectionMath.SECTION_SIZE];
        byte[] msb = new byte[SectionMath.NIBBLE_SIZE];
        byte[] meta = new byte[SectionMath.NIBBLE_SIZE];
        prepare.set(0, 300, 5, false);
        prepare.applyVanilla(lsb, msb, meta, null, null);

        PendingSection section = new PendingSection();
        section.set(0, 1, 0, false);
        section.applyVanilla(lsb, msb, meta, null, null);

        assertEquals(1, lsb[0] & 0xFF);
        assertEquals("stale MSB nibble must be cleared", 0, SectionMath.nibbleGet(msb, 0));
        assertEquals("stale metadata must be cleared", 0, SectionMath.nibbleGet(meta, 0));
    }

    @Test
    public void applyVanillaWithoutMsbArray() {
        PendingSection section = new PendingSection();
        byte[] lsb = new byte[SectionMath.SECTION_SIZE];
        byte[] meta = new byte[SectionMath.NIBBLE_SIZE];

        section.set(0, 200, 3, false);
        int written = section.applyVanilla(lsb, null, meta, null, null);
        assertEquals(1, written);
        assertEquals(200, lsb[0] & 0xFF);
        assertEquals(3, SectionMath.nibbleGet(meta, 0));
    }

    @Test
    public void applyVanillaOverflow() {
        PendingSection section = new PendingSection();
        byte[] lsb = new byte[SectionMath.SECTION_SIZE];
        byte[] msb = new byte[SectionMath.NIBBLE_SIZE];
        byte[] meta = new byte[SectionMath.NIBBLE_SIZE];

        //id 4096 does not fit 12 bits
        section.set(7, 4096, 9, true);
        //id 256 without an MSB array does not fit either
        section.set(8, 256, 0, false);

        OverflowCollector overflow = new OverflowCollector();
        int written = section.applyVanilla(lsb, msb, meta, null, overflow);
        assertEquals(1, written);
        assertEquals(1, overflow.blocks.size());
        assertArrayEquals(new int[]{7, 4096, 9, 1}, overflow.blocks.get(0));
        assertEquals("overflowed id must not be truncated into the arrays",
                0, lsb[7] & 0xFF);
        assertEquals(0, SectionMath.nibbleGet(meta, 7));
        //id 256 was written via the MSB array
        assertEquals(0, lsb[8] & 0xFF);
        assertEquals(1, SectionMath.nibbleGet(msb, 8));

        OverflowCollector overflow2 = new OverflowCollector();
        written = section.applyVanilla(lsb, null, meta, null, overflow2);
        assertEquals("id 256 without MSB array must overflow", 2, overflow2.blocks.size());
        assertEquals(0, written);
    }

    @Test
    public void applyVanillaChangedVisitor() {
        byte[] lsb = new byte[SectionMath.SECTION_SIZE];
        byte[] msb = new byte[SectionMath.NIBBLE_SIZE];
        byte[] meta = new byte[SectionMath.NIBBLE_SIZE];

        //Preexisting world content: id 300 data 5 at index 0, id 1 at index 1
        lsb[0] = (byte) 0x2C;
        SectionMath.nibbleSet(msb, 0, 1);
        SectionMath.nibbleSet(meta, 0, 5);
        lsb[1] = 1;

        PendingSection section = new PendingSection();
        section.set(0, 2, 0, false);   //changed (300:5 -> 2:0)
        section.set(1, 1, 0, false);   //unchanged (1:0 -> 1:0)
        section.set(2, 89, 1, false);  //changed (0:0 -> 89:1)

        ChangeCollector changes = new ChangeCollector();
        section.applyVanilla(lsb, msb, meta, changes, null);

        assertEquals(2, changes.changes.size());
        assertArrayEquals(new int[]{0, 300, 2}, changes.changes.get(0));
        assertArrayEquals(new int[]{2, 0, 89}, changes.changes.get(1));
    }

    @Test
    public void applyId16ExactValues() {
        PendingSection section = new PendingSection();
        short[] ids = new short[SectionMath.SECTION_SIZE];
        byte[] meta = new byte[SectionMath.NIBBLE_SIZE];

        section.set(0, 32000, 3, false);
        section.set(1, 65535, 15, false);
        section.set(2, 89, 0, false);

        int written = section.applyId16(ids, meta, null, null);
        assertEquals(3, written);

        assertEquals(32000, ids[0] & 0xFFFF);
        assertEquals((short) 32000, ids[0]);
        //65535 wraps to -1 as a raw short but reads back via & 0xFFFF
        assertEquals((short) -1, ids[1]);
        assertEquals(65535, ids[1] & 0xFFFF);
        assertEquals(89, ids[2]);

        assertEquals(0x3, SectionMath.nibbleGet(meta, 0));
        assertEquals(0xF, SectionMath.nibbleGet(meta, 1));
        assertEquals(0xF3, meta[0] & 0xFF);
    }

    @Test
    public void applyId16NoTruncationOfExtendedIds() {
        //16529 is the mangrove trapdoor style id that used to be truncated
        //to 12 bits (16529 & 0xFFF = 145, an anvil) by the buggy readers
        PendingSection section = new PendingSection();
        short[] ids = new short[SectionMath.SECTION_SIZE];
        byte[] meta = new byte[SectionMath.NIBBLE_SIZE];

        section.set(123, 16529, 2, false);
        section.applyId16(ids, meta, null, null);

        assertEquals(16529, ids[123] & 0xFFFF);
        assertFalse("id must not be truncated to 12 bits", (ids[123] & 0xFFFF) == 145);
    }

    @Test
    public void applyId16ChangedVisitorReadsOldShortsUnsigned() {
        short[] ids = new short[SectionMath.SECTION_SIZE];
        byte[] meta = new byte[SectionMath.NIBBLE_SIZE];
        ids[5] = (short) 40000; //stored as negative short

        PendingSection section = new PendingSection();
        section.set(5, 1, 0, false);

        ChangeCollector changes = new ChangeCollector();
        section.applyId16(ids, meta, changes, null);

        assertEquals(1, changes.changes.size());
        assertArrayEquals(new int[]{5, 40000, 1}, changes.changes.get(0));
    }
}
