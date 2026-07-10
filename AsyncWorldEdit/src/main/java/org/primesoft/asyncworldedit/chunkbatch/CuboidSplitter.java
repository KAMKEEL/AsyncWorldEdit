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

/**
 * Splits a world-space cuboid into per chunk-column boxes for the fast
 * lane compiler. Pure logic: no Bukkit/NMS/WorldEdit imports.
 *
 * @author KAMKEEL
 */
public final class CuboidSplitter {

    private CuboidSplitter() {
    }

    /**
     * Visitor for one chunk-column's share of the cuboid
     */
    public interface IChunkBoxVisitor {

        /**
         * @param cx chunk x
         * @param cz chunk z
         * @param x0 world min x of the box (inside chunk cx)
         * @param y0 world min y (clamped to 0)
         * @param z0 world min z (inside chunk cz)
         * @param x1 world max x
         * @param y1 world max y (clamped to 255)
         * @param z1 world max z
         * @return false to abort the split early (budget refusal / cancel)
         */
        boolean visit(int cx, int cz, int x0, int y0, int z0,
                int x1, int y1, int z1);
    }

    /**
     * Visit every chunk-column box of the cuboid [min..max] (inclusive),
     * y clamped to the world's 0..255 range. Iterates chunk columns in
     * z-major then x order (deterministic; the streaming drain orders
     * flushes independently by write sequence).
     *
     * @return true when every box was visited, false when the visitor
     * aborted or the y range is entirely outside the world
     */
    public static boolean forEachChunkBox(int minX, int minY, int minZ,
            int maxX, int maxY, int maxZ, IChunkBoxVisitor visitor) {
        final int y0 = Math.max(0, minY);
        final int y1 = Math.min(255, maxY);
        if (y0 > y1 || minX > maxX || minZ > maxZ) {
            return false;
        }

        final int cx0 = SectionMath.blockToChunk(minX);
        final int cx1 = SectionMath.blockToChunk(maxX);
        final int cz0 = SectionMath.blockToChunk(minZ);
        final int cz1 = SectionMath.blockToChunk(maxZ);

        for (int cz = cz0; cz <= cz1; cz++) {
            final int bz0 = Math.max(minZ, cz << 4);
            final int bz1 = Math.min(maxZ, (cz << 4) + 15);
            for (int cx = cx0; cx <= cx1; cx++) {
                final int bx0 = Math.max(minX, cx << 4);
                final int bx1 = Math.min(maxX, (cx << 4) + 15);
                if (!visitor.visit(cx, cz, bx0, y0, bz0, bx1, y1, bz1)) {
                    return false;
                }
            }
        }
        return true;
    }
}
