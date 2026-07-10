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

/**
 * Flush-time undo capture target of one edit session. The buffered engine
 * calls it on the server main thread: once per flushed chunk section (the
 * begin/capture/end bracket, slots ascending) and once per buffered block
 * that a classic path write cleared before it could flush. Implementations
 * synchronize internally; the capture entry points and the undo iteration
 * never run concurrently with each other from the engine's point of view,
 * but the object change recording happens on the producer thread.
 *
 * @author KAMKEEL
 */
public interface ICaptureSink {

    /**
     * Open the capture bracket of one chunk section flush.
     *
     * @param cx chunk x
     * @param cz chunk z
     * @param section section index (0..15)
     * @param firstSeq lowest write sequence of the section's pending slots
     * @param lastSeq highest write sequence of the section's pending slots
     */
    void beginSection(int cx, int cz, int section, int firstSeq, int lastSeq);

    /**
     * Capture one pending slot of the open section, old value read BEFORE
     * the write. Slots arrive in ascending index order.
     *
     * @param slotIndex slot in the section (0..4095)
     * @param oldId old block id
     * @param oldData old metadata
     * @param newId new block id
     * @param newData new metadata
     */
    void capture(int slotIndex, int oldId, int oldData, int newId, int newData);

    /**
     * Close the capture bracket of the section flush
     */
    void endSection();

    /**
     * Capture a buffered block that a classic path write is about to
     * overwrite (the pending value never reaches the world, but its undo
     * anchor - the world value before the job's write - must survive).
     *
     * @param x world x
     * @param y world y
     * @param z world z
     * @param oldId world block id at clear time
     * @param oldData world metadata at clear time
     * @param newId the cleared pending block id
     * @param newData the cleared pending metadata
     * @param seq the cleared pending write sequence
     */
    void captureCleared(int x, int y, int z, int oldId, int oldData,
            int newId, int newData, int seq);

    /**
     * The owning job finished and its sink was unregistered: no further
     * capture may be accepted (a late capture attempt - e.g. a stale
     * registration hit after the job id was reused - must fail loudly
     * instead of corrupting the sealed history) and held memory may be
     * released. The captured history itself stays fully replayable.
     */
    void jobDone();
}
