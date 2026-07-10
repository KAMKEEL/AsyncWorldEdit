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

import com.sk89q.worldedit.BlockVector;
import com.sk89q.worldedit.WorldEditException;
import com.sk89q.worldedit.blocks.BaseBlock;
import com.sk89q.worldedit.history.change.BlockChange;
import static org.primesoft.asyncworldedit.LoggerProvider.log;
import org.primesoft.asyncworldedit.chunkbatch.undo.ColumnarUndoLog;
import org.primesoft.asyncworldedit.chunkbatch.undo.ICaptureSink;

/**
 * The capture sink of one buffered job: brackets the flush-time section
 * captures into the job's {@link ColumnarUndoLog} and routes cleared
 * pending blocks (a buffered value a classic path write overwrote before
 * it could flush) into the session's object change set, where they keep
 * the write-order position the classic record expects.
 *
 * Any capture failure (spool I/O, log already closed) marks the sink
 * broken and is logged once: further captures become no-ops and the flush
 * itself is never failed - the composite change set falls back to whatever
 * was captured so far.
 *
 * Threading: the section bracket runs on the server main thread (the
 * drain), the log replay runs after the job finished, and both synchronize
 * on the log; {@link #captureCleared} runs on the producer thread and only
 * touches the (thread safe) session change set.
 *
 * @author KAMKEEL
 */
public class ColumnarUndoSink implements ICaptureSink {

    private final ColumnarUndoLog m_log;

    /**
     * The session's extended change set (thread safe), the target of the
     * cleared-block compensation records
     */
    private final IExtendedChangeSet m_aweChangeSet;

    /**
     * Set on the first capture failure; guarded by {@link #m_log}
     */
    private boolean m_broken;

    /**
     * A cleared-block record failed already (logged once)
     */
    private boolean m_clearErrorLogged;

    /**
     * @param undoLog the job's columnar undo log
     * @param aweChangeSet the session's extended change set for the
     * cleared-block records
     */
    public ColumnarUndoSink(ColumnarUndoLog undoLog, IExtendedChangeSet aweChangeSet) {
        if (undoLog == null) {
            throw new IllegalArgumentException("Undo log is null");
        }
        m_log = undoLog;
        m_aweChangeSet = aweChangeSet;
    }

    /**
     * The wrapped columnar undo log (iterated by the composite change set,
     * synchronized on the log itself)
     */
    public ColumnarUndoLog getLog() {
        return m_log;
    }

    @Override
    public void beginSection(int cx, int cz, int section, int firstSeq, int lastSeq) {
        synchronized (m_log) {
            if (m_broken) {
                return;
            }
            try {
                m_log.beginSection(cx, cz, section, firstSeq, lastSeq);
            } catch (Throwable ex) {
                markBroken(ex);
            }
        }
    }

    @Override
    public void capture(int slotIndex, int oldId, int oldData, int newId, int newData) {
        synchronized (m_log) {
            if (m_broken) {
                return;
            }
            try {
                m_log.capture(slotIndex, oldId, oldData, newId, newData);
            } catch (Throwable ex) {
                markBroken(ex);
            }
        }
    }

    @Override
    public void endSection() {
        synchronized (m_log) {
            if (m_broken) {
                return;
            }
            try {
                m_log.endSection();
            } catch (Throwable ex) {
                markBroken(ex);
            }
        }
    }

    @Override
    public void captureCleared(int x, int y, int z, int oldId, int oldData,
            int newId, int newData, int seq) {
        if (m_aweChangeSet == null) {
            return;
        }

        try {
            //ThreadSafeChangeSet below the memory monitor makes this safe
            //from the producer thread
            m_aweChangeSet.addExtended(new BlockChange(new BlockVector(x, y, z),
                    new BaseBlock(oldId, oldData), new BaseBlock(newId, newData)), null);
        } catch (WorldEditException ex) {
            if (!m_clearErrorLogged) {
                m_clearErrorLogged = true;
                log("Error while recording a cleared buffered block for undo: " + ex);
            }
        }
    }

    @Override
    public void jobDone() {
        synchronized (m_log) {
            //Seal the log: a late capture (e.g. a stale registration hit
            //after the job id was reused) now fails loudly through
            //markBroken instead of silently appending to this job's
            //finished history. Replay (undo and redo) stays available.
            m_log.seal();
        }
    }

    /**
     * Close the log: release its memory and delete the spool file.
     * Idempotent; called by the composite change set when the session is
     * released.
     */
    public void close() {
        synchronized (m_log) {
            m_broken = true;
            m_log.close();
        }
    }

    private void markBroken(Throwable ex) {
        m_broken = true;
        log("Error while capturing columnar undo (undo of this job may be"
                + " partial): " + ex);
    }
}
