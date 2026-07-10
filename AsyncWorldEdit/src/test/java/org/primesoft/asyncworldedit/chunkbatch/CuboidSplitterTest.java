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
 * Exact-value tests for the world-cuboid to chunk-column box splitter.
 *
 * @author KAMKEEL
 */
public class CuboidSplitterTest {

    private static final class Collector implements CuboidSplitter.IChunkBoxVisitor {

        final List<String> boxes = new ArrayList<String>();
        long volume;
        int abortAfter = -1;

        @Override
        public boolean visit(int cx, int cz, int x0, int y0, int z0,
                int x1, int y1, int z1) {
            boxes.add(cx + "," + cz + ":" + x0 + ".." + x1
                    + "/" + y0 + ".." + y1 + "/" + z0 + ".." + z1);
            volume += (long) (x1 - x0 + 1) * (y1 - y0 + 1) * (z1 - z0 + 1);
            return abortAfter < 0 || boxes.size() < abortAfter;
        }
    }

    @Test
    public void singleChunkBoxIsPassedThrough() {
        Collector c = new Collector();
        assertTrue(CuboidSplitter.forEachChunkBox(1, 5, 2, 14, 20, 13, c));
        assertEquals(1, c.boxes.size());
        assertEquals("0,0:1..14/5..20/2..13", c.boxes.get(0));
    }

    @Test
    public void spanningBoxSplitsAtChunkBordersAndKeepsTheVolume() {
        Collector c = new Collector();
        //x -5..20 spans chunks -1..1; z 10..40 spans chunks 0..2
        assertTrue(CuboidSplitter.forEachChunkBox(-5, 0, 10, 20, 3, 40, c));
        assertEquals(9, c.boxes.size());
        assertEquals(26L * 4L * 31L, c.volume);
        //Every box lies inside its chunk
        for (String box : c.boxes) {
            String[] main = box.split(":");
            String[] cc = main[0].split(",");
            String[] ranges = main[1].split("/");
            int cx = Integer.parseInt(cc[0]);
            int cz = Integer.parseInt(cc[1]);
            String[] xs = ranges[0].split("\\.\\.");
            String[] zs = ranges[2].split("\\.\\.");
            assertEquals(cx, SectionMath.blockToChunk(Integer.parseInt(xs[0])));
            assertEquals(cx, SectionMath.blockToChunk(Integer.parseInt(xs[1])));
            assertEquals(cz, SectionMath.blockToChunk(Integer.parseInt(zs[0])));
            assertEquals(cz, SectionMath.blockToChunk(Integer.parseInt(zs[1])));
        }
    }

    @Test
    public void yIsClampedToTheWorldRange() {
        Collector c = new Collector();
        assertTrue(CuboidSplitter.forEachChunkBox(0, -20, 0, 5, 300, 5, c));
        assertEquals("0,0:0..5/0..255/0..5", c.boxes.get(0));
    }

    @Test
    public void entirelyOutOfWorldYReturnsFalseAndVisitsNothing() {
        Collector c = new Collector();
        assertFalse(CuboidSplitter.forEachChunkBox(0, 300, 0, 5, 310, 5, c));
        assertTrue(c.boxes.isEmpty());
    }

    @Test
    public void visitorAbortStopsTheSplit() {
        Collector c = new Collector();
        c.abortAfter = 3;
        assertFalse(CuboidSplitter.forEachChunkBox(0, 0, 0, 100, 0, 100, c));
        assertEquals(3, c.boxes.size());
    }
}
