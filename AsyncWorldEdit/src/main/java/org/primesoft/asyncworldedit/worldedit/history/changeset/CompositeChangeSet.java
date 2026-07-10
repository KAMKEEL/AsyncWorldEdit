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
import com.sk89q.worldedit.blocks.BaseBlock;
import com.sk89q.worldedit.history.change.BlockChange;
import com.sk89q.worldedit.history.change.Change;
import com.sk89q.worldedit.history.changeset.ChangeSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.CopyOnWriteArrayList;
import static org.primesoft.asyncworldedit.LoggerProvider.log;
import org.primesoft.asyncworldedit.api.utils.IDisposable;
import org.primesoft.asyncworldedit.chunkbatch.SectionMath;
import org.primesoft.asyncworldedit.chunkbatch.undo.ColumnarUndoLog;
import org.primesoft.asyncworldedit.chunkbatch.undo.ColumnarUndoRegistry;

/**
 * The session root change set of the columnar undo mode: the object change
 * set (FileChangeSet on disk undo, else BlockOptimizedHistory) records the
 * tile/NBT/classic-path changes exactly as before, and every buffered
 * job's {@link ColumnarUndoLog} is attached in job start order. Undo and
 * redo iterate both sources lazily, materializing one BlockChange per
 * captured slot on demand - the whole point of the columnar mode is that
 * the per block change objects never exist all at once.
 *
 * <p>
 * Ordering correctness (columnar-then-object replay, an ACCEPTED
 * deviation from the plan's original global-sequence merge - recorded in
 * docs/prime-engine-plan.md): within one source the order is already
 * correct (object = insertion order, columnar = flush order with first
 * capture per slot plus redo-only rewrite segments in append order, so a
 * cross-flush rewrite forward-replays to its LAST value while backward
 * skips it). A position can appear in BOTH sources only as a classic
 * write followed by a buffered rewrite: the reverse direction is excluded
 * because a classic write over a still-buffered value clears the pending
 * block (clearBuffered), so the flush never captures that position, and a
 * budget-refused buffered write compensates into the OBJECT change set
 * (see ColumnarUndoSink.captureCleared), not the columnar log. The
 * backward (undo) iteration therefore replays all columnar changes first
 * and the object changes second: the later buffered rewrite is undone
 * before the earlier classic write, so the classic write's old value wins
 * - correct. Forward (redo) is the exact mirror: object first, columnar
 * second - the buffered rewrite lands last and, within the columnar
 * source, its redo-only rewrite segments land after its first capture, so
 * every position ends at its final value.</p>
 *
 * <p>
 * The iterators implement {@link ThreadSafeChangeSet.IThreadSafeIterator}
 * (so the thread safe wrapper forwards them instead of eagerly copying
 * every change into a list) and {@link IDisposable} (UndoProcessor
 * disposes the iterator after the replay). Dispose releases only the
 * object iterator's resources - it must NOT delete the columnar spool
 * files, because a redo after the undo still needs them. The spools die
 * with {@link #close}, called when the session leaves the history
 * (SerializableSessionList.releaseSession).</p>
 *
 * @author KAMKEEL
 */
public class CompositeChangeSet implements ChangeSet {

    /**
     * The object change set (FileChangeSet or BlockOptimizedHistory)
     */
    private final ChangeSet m_objectChangeSet;

    /**
     * The attached job sinks in job start order (producers attach, the
     * undo command iterates)
     */
    private final List<ColumnarUndoSink> m_attached
            = new CopyOnWriteArrayList<ColumnarUndoSink>();

    /**
     * A segment could not be read (logged once, the iteration falls back
     * to whatever it has)
     */
    private boolean m_segmentErrorLogged;

    public CompositeChangeSet(ChangeSet objectChangeSet) {
        if (objectChangeSet == null) {
            throw new IllegalArgumentException("Object change set is null");
        }
        m_objectChangeSet = objectChangeSet;
    }

    /**
     * The wrapped object change set (unwrap seam for the FileChangeSet
     * lifecycle sites)
     */
    public ChangeSet getObjectChangeSet() {
        return m_objectChangeSet;
    }

    /**
     * Attach the capture sink of a job that started recording columnar
     * undo. Called by the extent on the job's producer thread at first
     * suppression.
     */
    public void attach(ColumnarUndoSink sink) {
        if (sink != null) {
            m_attached.add(sink);
        }
    }

    /**
     * Number of attached job logs (telemetry + tests)
     */
    public int getAttachedCount() {
        return m_attached.size();
    }

    @Override
    public void add(Change change) {
        m_objectChangeSet.add(change);
    }

    @Override
    public Iterator<Change> backwardIterator() {
        return new CompositeIterator(true);
    }

    @Override
    public Iterator<Change> forwardIterator() {
        return new CompositeIterator(false);
    }

    @Override
    public int size() {
        long total = m_objectChangeSet.size();
        for (ColumnarUndoSink sink : m_attached) {
            final ColumnarUndoLog undoLog = sink.getLog();
            synchronized (undoLog) {
                total += undoLog.getCaptureCount();
            }
        }
        return total > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) total;
    }

    /**
     * Release the columnar side: drop the registry registrations (safety
     * net for jobs that registered but never buffered) and close every
     * log, which deletes the spool files. The object change set is closed
     * by its own lifecycle (releaseSession).
     */
    public void close() {
        for (ColumnarUndoSink sink : m_attached) {
            ColumnarUndoRegistry.unregisterSink(sink);
            sink.close();
        }
        m_attached.clear();
    }

    /**
     * The lazy two-source iterator. Backward: attached logs in reverse
     * attach order (each log segments in reverse, runs in reverse, slots
     * of a run in reverse), then the object change set backward. Forward:
     * the object change set first, then the logs in attach order, each
     * fully forward. Unreadable segments are skipped (logged once).
     */
    private final class CompositeIterator implements Iterator<Change>,
            ThreadSafeChangeSet.IThreadSafeIterator, IDisposable,
            IColumnarRunSource {

        private final boolean m_backward;

        /**
         * The logs in iteration order
         */
        private final List<ColumnarUndoLog> m_logs;

        private int m_logIdx;

        /**
         * Segment cursor of the current log, -1 before the first fetch
         */
        private int m_segIdx = -1;

        /**
         * The decoded runs of the current segment plus its world base
         * coordinates
         */
        private int[] m_runs;
        private int m_runCount;
        private int m_bx;
        private int m_by;
        private int m_bz;

        private int m_runIdx;

        /**
         * Position inside the current run (0..len-1, in emit order)
         */
        private int m_slotK;

        /**
         * The object iterator, created lazily when its phase starts
         */
        private Iterator<Change> m_objectIterator;

        private Change m_next;

        CompositeIterator(boolean backward) {
            m_backward = backward;
            m_logs = new ArrayList<ColumnarUndoLog>(m_attached.size());
            for (ColumnarUndoSink sink : m_attached) {
                m_logs.add(sink.getLog());
            }
            if (backward) {
                //Backward: last attached job first
                Collections.reverse(m_logs);
            }
        }

        @Override
        public boolean hasNext() {
            if (m_next == null) {
                m_next = fetch();
            }
            return m_next != null;
        }

        @Override
        public Change next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            final Change change = m_next;
            m_next = null;
            return change;
        }

        @Override
        public void remove() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void dispose() {
            //Release the object iterator's resources (an undo file
            //iterator holds an open stream). The columnar logs are NOT
            //closed here: a redo after this undo replays the same spools;
            //they are deleted by CompositeChangeSet.close when the session
            //leaves the history.
            if (m_objectIterator instanceof IDisposable) {
                ((IDisposable) m_objectIterator).dispose();
            }
        }

        /**
         * Offer the next pending change as a whole columnar run (see
         * {@link IColumnarRunSource}). Emission-order equivalence with
         * the per-change iteration: within a run every slot carries the
         * SAME (old, new) values, and runs/segments/logs are advanced by
         * exactly the same cursor logic as {@link #fetchColumnar}, so
         * consuming a run wholesale is indistinguishable from iterating
         * its slots - the fill's write sequence replaces the per-slot
         * order, which only matters across DIFFERENT values (first
         * capture per slot keeps a slot out of every later non-redo-only
         * segment, so equal-position ordering within one log never
         * arises; across logs and against object changes the caller's
         * phase order is preserved because this method never crosses a
         * phase boundary).
         */
        @Override
        public boolean nextRun(IRunConsumer consumer) {
            if (m_next != null) {
                //A hasNext() lookahead already materialized a change; it
                //must be emitted through next() first
                return false;
            }

            if (!m_backward) {
                //Forward: the object phase replays first, per change
                if (m_objectIterator == null) {
                    m_objectIterator = m_objectChangeSet.forwardIterator();
                }
                if (m_objectIterator != null && m_objectIterator.hasNext()) {
                    return false;
                }
            }

            for (;;) {
                if (m_runs != null && m_runIdx >= 0 && m_runIdx < m_runCount) {
                    final int len = ColumnarUndoLog.runLength(m_runs, m_runIdx);
                    if (m_slotK < len) {
                        //Offer the REMAINDER of the current run as one
                        //interval: backward emits slots from the end, so
                        //the remainder is the prefix; forward the suffix
                        final int start = ColumnarUndoLog.runStartSlot(m_runs, m_runIdx)
                                + (m_backward ? 0 : m_slotK);
                        if (!consumer.run(m_bx, m_by, m_bz,
                                start, len - m_slotK,
                                ColumnarUndoLog.runOldId(m_runs, m_runIdx),
                                ColumnarUndoLog.runOldData(m_runs, m_runIdx),
                                ColumnarUndoLog.runNewId(m_runs, m_runIdx),
                                ColumnarUndoLog.runNewData(m_runs, m_runIdx))) {
                            return false;
                        }
                        m_slotK = len;
                        return true;
                    }
                    //Run done: advance exactly like fetchColumnar
                    m_runIdx += m_backward ? -1 : 1;
                    m_slotK = 0;
                    continue;
                }

                if (m_logIdx >= m_logs.size()) {
                    return false;
                }

                if (!advanceSegment()) {
                    m_logIdx++;
                    m_segIdx = -1;
                }
            }
        }

        private Change fetch() {
            if (m_backward) {
                final Change columnar = fetchColumnar();
                if (columnar != null) {
                    return columnar;
                }
                return fetchObject();
            }

            final Change object = fetchObject();
            if (object != null) {
                return object;
            }
            return fetchColumnar();
        }

        /**
         * The next object change, null when exhausted. Forward mode marks
         * the first phase done so fetch does not re-enter after the
         * columnar phase started.
         */
        private Change fetchObject() {
            if (m_objectIterator == null) {
                m_objectIterator = m_backward
                        ? m_objectChangeSet.backwardIterator()
                        : m_objectChangeSet.forwardIterator();
            }
            return m_objectIterator != null && m_objectIterator.hasNext()
                    ? m_objectIterator.next() : null;
        }

        /**
         * The next columnar change, null when every log is exhausted
         */
        private Change fetchColumnar() {
            for (;;) {
                if (m_runs != null && m_runIdx >= 0 && m_runIdx < m_runCount) {
                    final int len = ColumnarUndoLog.runLength(m_runs, m_runIdx);
                    if (m_slotK < len) {
                        return emit(len);
                    }
                    //Run done: advance to the next run of the segment
                    m_runIdx += m_backward ? -1 : 1;
                    m_slotK = 0;
                    continue;
                }

                if (m_logIdx >= m_logs.size()) {
                    return null;
                }

                if (!advanceSegment()) {
                    //Current log exhausted: next log
                    m_logIdx++;
                    m_segIdx = -1;
                }
            }
        }

        /**
         * Load the next segment of the current log (in iteration order).
         * Backward (undo) iteration skips redo-only rewrite segments:
         * their old columns are the job's own intermediate values, only
         * the forward (redo) replay may emit them.
         *
         * @return false when the log has no further segment
         */
        private boolean advanceSegment() {
            final ColumnarUndoLog undoLog = m_logs.get(m_logIdx);

            synchronized (undoLog) {
                final int count = undoLog.getSegmentCount();
                do {
                    if (m_segIdx == -1) {
                        m_segIdx = m_backward ? count - 1 : 0;
                    } else {
                        m_segIdx += m_backward ? -1 : 1;
                    }
                    if (m_segIdx < 0 || m_segIdx >= count) {
                        m_runs = null;
                        return false;
                    }
                } while (m_backward && undoLog.isSegmentRedoOnly(m_segIdx));

                try {
                    m_runs = undoLog.loadSegmentRuns(m_segIdx);
                    m_runCount = undoLog.getSegmentRunCount(m_segIdx);
                    m_bx = undoLog.getSegmentChunkX(m_segIdx) << 4;
                    m_bz = undoLog.getSegmentChunkZ(m_segIdx) << 4;
                    m_by = undoLog.getSegmentSection(m_segIdx) << 4;
                } catch (Throwable ex) {
                    //Integrity fallback: skip the unreadable segment, keep
                    //replaying everything else
                    if (!m_segmentErrorLogged) {
                        m_segmentErrorLogged = true;
                        log("Error while reading a columnar undo segment"
                                + " (undo may be partial): " + ex);
                    }
                    m_runs = null;
                    m_runCount = 0;
                    return true;
                }
            }

            m_runIdx = m_backward ? m_runCount - 1 : 0;
            m_slotK = 0;
            return true;
        }

        /**
         * Materialize the BlockChange of the current run position and step
         * the cursor
         */
        private Change emit(int len) {
            final int start = ColumnarUndoLog.runStartSlot(m_runs, m_runIdx);
            final int slot = m_backward
                    ? start + len - 1 - m_slotK : start + m_slotK;
            m_slotK++;

            return new BlockChange(
                    new BlockVector(
                            m_bx + SectionMath.indexToX(slot),
                            m_by + SectionMath.indexToY(slot),
                            m_bz + SectionMath.indexToZ(slot)),
                    new BaseBlock(
                            ColumnarUndoLog.runOldId(m_runs, m_runIdx),
                            ColumnarUndoLog.runOldData(m_runs, m_runIdx)),
                    new BaseBlock(
                            ColumnarUndoLog.runNewId(m_runs, m_runIdx),
                            ColumnarUndoLog.runNewData(m_runs, m_runIdx)));
        }
    }
}
