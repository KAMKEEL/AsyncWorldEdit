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
package org.primesoft.asyncworldedit.worldedit.history.changeset;

/**
 * A change iterator whose columnar phase can be consumed RUN-wise: a run
 * is a contiguous section slot interval with one constant (old, new) value
 * pair - by construction of the columnar undo log's RLE encoding. The
 * undo/redo processors use this to lower whole runs into bulk buffer
 * fills instead of materializing one BlockChange object per block.
 *
 * The per-change {@link java.util.Iterator} view stays fully functional
 * and interchangeable at any point: a refused (or never requested) run is
 * simply emitted change-by-change, and a partially emitted run is offered
 * as its remaining interval.
 *
 * @author KAMKEEL
 */
public interface IColumnarRunSource {

    /**
     * Receiver of one columnar run
     */
    interface IRunConsumer {

        /**
         * Consume one whole run.
         *
         * @param bx world x of the section base (chunkX * 16)
         * @param by world y of the section base (section * 16)
         * @param bz world z of the section base (chunkZ * 16)
         * @param startSlot first section slot of the interval (0..4095)
         * @param len interval length
         * @param oldId old block id of every slot in the run
         * @param oldData old metadata of every slot in the run
         * @param newId new block id of every slot in the run
         * @param newData new metadata of every slot in the run
         * @return true when the run was consumed wholesale; false when the
         * consumer cannot take it - the source does NOT advance and the
         * run is emitted through the per-change iterator instead
         */
        boolean run(int bx, int by, int bz, int startSlot, int len,
                int oldId, int oldData, int newId, int newData);
    }

    /**
     * Offer the next pending change as a whole columnar run.
     *
     * Returns true when a run was offered to the consumer AND consumed
     * (the source advanced past it). Returns false when the immediate
     * next item is not a columnar run: a change was already materialized
     * by a hasNext() lookahead, the current phase is the object change
     * phase (forward iteration replays object changes first), the
     * columnar phase is exhausted, or the consumer refused the run.
     * The caller then processes one change through the regular iterator
     * and may try again.
     */
    boolean nextRun(IRunConsumer consumer);
}
