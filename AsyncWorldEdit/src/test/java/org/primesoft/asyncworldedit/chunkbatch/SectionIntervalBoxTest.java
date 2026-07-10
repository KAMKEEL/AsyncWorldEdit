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
 * Exact-value tests for the slot-interval to box decomposition that
 * lowers columnar undo runs into bulk buffer fills.
 *
 * @author KAMKEEL
 */
public class SectionIntervalBoxTest {

    /**
     * Collect the boxes of an interval as "x0,y0,z0-x1,y1,z1" strings
     */
    private static List<String> boxes(int start, int len) {
        final List<String> result = new ArrayList<String>();
        assertTrue(SectionMath.intervalToBoxes(start, len,
                new SectionMath.IIntervalBoxVisitor() {
            @Override
            public boolean box(int x0, int y0, int z0, int x1, int y1, int z1) {
                result.add(x0 + "," + y0 + "," + z0 + "-" + x1 + "," + y1 + "," + z1);
                return true;
            }
        }));
        return result;
    }

    /**
     * Verify the boxes of [start, start+len) cover exactly those slots
     */
    private static void assertExactCoverage(int start, int len) {
        final boolean[] covered = new boolean[SectionMath.SECTION_SIZE];
        assertTrue(SectionMath.intervalToBoxes(start, len,
                new SectionMath.IIntervalBoxVisitor() {
            @Override
            public boolean box(int x0, int y0, int z0, int x1, int y1, int z1) {
                for (int y = y0; y <= y1; y++) {
                    for (int z = z0; z <= z1; z++) {
                        for (int x = x0; x <= x1; x++) {
                            int index = SectionMath.sectionIndex(x, y, z);
                            assertFalse("slot covered twice: " + index, covered[index]);
                            covered[index] = true;
                        }
                    }
                }
                return true;
            }
        }));

        for (int i = 0; i < SectionMath.SECTION_SIZE; i++) {
            assertEquals("slot " + i, i >= start && i < start + len, covered[i]);
        }
    }

    @Test
    public void fullSectionIsOneBox() {
        List<String> b = boxes(0, 4096);
        assertEquals(1, b.size());
        assertEquals("0,0,0-15,15,15", b.get(0));
    }

    @Test
    public void alignedPlanesAndRows() {
        //Two full planes starting at y=2
        List<String> b = boxes(2 * 256, 512);
        assertEquals(1, b.size());
        assertEquals("0,2,0-15,3,15", b.get(0));

        //Three full rows starting at z=5 of plane y=1
        b = boxes(256 + 5 * 16, 48);
        assertEquals(1, b.size());
        assertEquals("0,1,5-15,1,7", b.get(0));
    }

    @Test
    public void unalignedIntervalDecomposesIntoLeadRowsPlanesTail() {
        //Start mid-row at (x=3, z=15, y=0), length crosses into plane 1
        //and ends mid-row: [253, 253+300) = lead 3 slots (x3..15 of the
        //last row of plane 0), plane 1 whole (256), tail 41 = 2 rows + 9
        List<String> b = boxes(253, 300);
        assertEquals(4, b.size());
        assertEquals("13,0,15-15,0,15", b.get(0));
        assertEquals("0,1,0-15,1,15", b.get(1));
        assertEquals("0,2,0-15,2,1", b.get(2));
        assertEquals("0,2,2-8,2,2", b.get(3));
        assertExactCoverage(253, 300);
    }

    @Test
    public void singleSlotAndShortRuns() {
        List<String> b = boxes(4095, 1);
        assertEquals(1, b.size());
        assertEquals("15,15,15-15,15,15", b.get(0));

        assertExactCoverage(0, 1);
        assertExactCoverage(17, 15);
        assertExactCoverage(15, 2);
    }

    @Test
    public void coverageIsExactForManyIntervals() {
        int[][] cases = {
            {0, 4096}, {0, 16}, {1, 14}, {8, 8}, {8, 16}, {8, 264},
            {255, 1}, {255, 2}, {256, 255}, {256, 257}, {100, 3000},
            {4000, 96}, {240, 32}, {15, 4081}
        };
        for (int[] c : cases) {
            assertExactCoverage(c[0], c[1]);
        }
    }

    @Test
    public void visitorAbortStopsTheDecomposition() {
        final int[] calls = {0};
        boolean completed = SectionMath.intervalToBoxes(253, 300,
                new SectionMath.IIntervalBoxVisitor() {
            @Override
            public boolean box(int x0, int y0, int z0, int x1, int y1, int z1) {
                calls[0]++;
                return calls[0] < 2;
            }
        });
        assertFalse(completed);
        assertEquals(2, calls[0]);
    }
}
