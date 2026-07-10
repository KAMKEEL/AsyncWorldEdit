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

import org.primesoft.asyncworldedit.chunkbatch.PendingChunk;
import org.primesoft.asyncworldedit.chunkbatch.PendingSection;
import org.primesoft.asyncworldedit.chunkbatch.SectionMath;

/**
 * Captures the pre-write old values of every pending slot of a chunk into
 * a capture sink: one begin/capture/end bracket per section holding
 * pending blocks, slots in ascending index order, the section's write
 * sequence range scanned from the pending slots. Runs BEFORE the chunk is
 * written (direct section write or classic replay), so the reader still
 * sees the old world values.
 *
 * Pure logic: no Bukkit/NMS imports; the old values come from the
 * injected reader (raw NMS section reads in production, the WorldEdit
 * world as fallback, fakes in tests).
 *
 * @author KAMKEEL
 */
public final class ChunkCaptureUtil {

    private ChunkCaptureUtil() {
    }

    /**
     * Capture one pending chunk into a sink.
     *
     * @param pending the pending chunk about to be flushed
     * @param sink the capture sink
     * @param oldReader packed old value reader (pre-write world state)
     * @return number of pending slots whose old value could not be read
     * (skipped; the caller logs once when non zero)
     */
    public static int capture(PendingChunk pending, ICaptureSink sink,
            ISlotReader oldReader) {
        final int bx = pending.getX() << 4;
        final int bz = pending.getZ() << 4;
        int misses = 0;

        for (int s = 0; s < SectionMath.SECTIONS_PER_CHUNK; s++) {
            final PendingSection ps = pending.getSection(s);
            if (ps == null || ps.getCount() == 0) {
                continue;
            }

            int firstSeq = Integer.MAX_VALUE;
            int lastSeq = Integer.MIN_VALUE;
            for (int index = 0; index < SectionMath.SECTION_SIZE; index++) {
                if (ps.getSlot(index) == SectionMath.EMPTY_SLOT) {
                    continue;
                }
                final int seq = ps.getSeq(index);
                if (seq < firstSeq) {
                    firstSeq = seq;
                }
                if (seq > lastSeq) {
                    lastSeq = seq;
                }
            }

            final int by = s << 4;
            sink.beginSection(pending.getX(), pending.getZ(), s, firstSeq, lastSeq);
            try {
                for (int index = 0; index < SectionMath.SECTION_SIZE; index++) {
                    final int slot = ps.getSlot(index);
                    if (slot == SectionMath.EMPTY_SLOT) {
                        continue;
                    }

                    final int old = oldReader.read(
                            bx + SectionMath.indexToX(index),
                            by + SectionMath.indexToY(index),
                            bz + SectionMath.indexToZ(index));
                    if (old == SectionMath.EMPTY_SLOT) {
                        //Unreadable old value: drop the slot from the undo
                        //record but MARK it captured - otherwise a later
                        //re-flush of this section would capture the job's
                        //own intermediate value as "old" (the caller logs
                        //the miss count once)
                        sink.markMissing(index);
                        misses++;
                        continue;
                    }

                    sink.capture(index,
                            SectionMath.slotId(old), SectionMath.slotData(old),
                            SectionMath.slotId(slot), SectionMath.slotData(slot));
                }
            } finally {
                sink.endSection();
            }
        }

        return misses;
    }
}
