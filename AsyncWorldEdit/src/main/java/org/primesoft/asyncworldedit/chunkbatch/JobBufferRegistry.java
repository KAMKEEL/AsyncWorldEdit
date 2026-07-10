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

import com.sk89q.worldedit.world.World;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import static org.primesoft.asyncworldedit.LoggerProvider.log;
import org.primesoft.asyncworldedit.api.IWorld;
import org.primesoft.asyncworldedit.api.blockPlacer.entries.IJobEntry;
import org.primesoft.asyncworldedit.api.blockPlacer.entries.JobStatus;
import org.primesoft.asyncworldedit.api.playerManager.IPlayerEntry;
import org.primesoft.asyncworldedit.blockPlacer.entries.JobEntry;
import org.primesoft.asyncworldedit.chunkbatch.undo.ColumnarUndoRegistry;
import org.primesoft.asyncworldedit.chunkbatch.undo.ICaptureSink;

/**
 * Central coordinator of the buffer-first block placement engine.
 *
 * Every batchable async block write of a job is buffered directly by the
 * job's producer thread into a per job {@link JobBuffer} - no per block
 * queue entry object is ever created. The block placer drains the buffers
 * round robin on the server main thread and flushes ready chunks through
 * the direct chunk section writer.
 *
 * Concurrency model:
 * <ul>
 * <li>A single async task drives one job, so a given {@link JobBuffer} has
 * exactly one producer thread; the only concurrency is producer vs. the
 * main thread drain/clear.</li>
 * <li>The chunk map and chunk order queue of a buffer are concurrent
 * collections; every {@link PendingChunk} touch (produce, overlay read,
 * clear, drain flush) happens inside {@code synchronized (chunk)}.</li>
 * <li>The drain removes a chunk from the map (under the chunk lock) BEFORE
 * flushing it, so a producer that raced with the drain re-checks the chunk
 * is still mapped and, if not, retries into a fresh chunk buffer instead of
 * writing into a chunk that is about to be flushed and discarded.</li>
 * </ul>
 *
 * Ordering correctness of the buffered vs. classic paths:
 * <ul>
 * <li>Buffered chunks flush when their job has finished producing, on
 * force, or - streaming - when the job's live section count exceeds the
 * window watermark or a chunk went stale (see {@link #drainRoundRobin});
 * classic queue entries are placed during the placer run. When a block
 * does not fit the buffer (section budget full) the producer falls back to
 * the classic path; that classic write clears any earlier buffered value
 * at the same position ({@link #clearBuffered}), so the newer classic
 * write is not overwritten by the stale buffered value at flush time. A
 * classic write to a position whose chunk was ALREADY streamed out finds
 * nothing buffered and is a plain world write over the flushed value -
 * still last-queued-wins.</li>
 * <li>A later buffered write to a position previously written classically
 * simply flushes after the classic write, so last-queued-wins holds in both
 * directions.</li>
 * <li>Within a job a global monotonic write sequence orders the last write
 * of every position so the classic replay of sub threshold chunks keeps
 * WorldEdit's support-before-attachment order.</li>
 * <li>A producer write that races a streamed flush of its chunk lands in a
 * fresh {@link PendingChunk} (the drain detaches the chunk from the map
 * under the chunk lock BEFORE flushing; the producer re-checks the mapping
 * inside the lock and retries). The fresh chunk flushes later, so
 * last-write-wins also holds across a mid-job flush.</li>
 * </ul>
 *
 * Undo is unaffected by streaming: in changeset mode WorldEdit's operation
 * time change set records the old block values when the producer writes
 * them, before anything is flushed to the world; in columnar mode the old
 * values are captured at flush time into the job's registered capture sink
 * (see {@link ColumnarUndoRegistry}) immediately BEFORE the chunk write,
 * and the first-capture-per-slot rule of the log keeps mid-job re-flushes
 * of a section from overwriting the original old values.
 *
 * @author KAMKEEL
 */
public final class JobBufferRegistry {

    private static final JobBufferRegistry s_instance = new JobBufferRegistry();

    public static JobBufferRegistry getInstance() {
        return s_instance;
    }

    /**
     * Sink that flushes one buffered chunk to the world. The production
     * sink delegates to the shared {@link ChunkBatchWriter} (direct NMS
     * write + classic replay + attachment deferral); tests inject a fake.
     */
    public interface IFlushSink {

        /**
         * Flush a single pending chunk of a job to the world. Main thread
         * only.
         *
         * @param weWorld the WorldEdit world (classic replay)
         * @param aweWorld the AWE world (direct chunk writes)
         * @param chunk the pending chunk (already detached from its buffer)
         * @param captureSink the job's registered undo capture sink; the
         * old value of every pending slot must be captured into it BEFORE
         * the chunk is written. Null when the job records undo through the
         * object change set (or not at all) - then no capture happens.
         */
        void flush(World weWorld, IWorld aweWorld, PendingChunk chunk,
                ICaptureSink captureSink);
    }

    /**
     * The production flush sink: hand the chunk to the shared chunk batch
     * writer so the NMS direct write path and its classic replay are reused
     */
    private static final IFlushSink PRODUCTION_SINK = new IFlushSink() {
        @Override
        public void flush(World weWorld, IWorld aweWorld, PendingChunk chunk,
                ICaptureSink captureSink) {
            ChunkBatchWriter.getInstance().flushJobChunk(weWorld, aweWorld, chunk,
                    captureSink);
        }
    };

    /**
     * Composite key: player uuid + job id
     */
    private static final class Key {

        final UUID uuid;
        final int jobId;

        Key(UUID uuid, int jobId) {
            this.uuid = uuid;
            this.jobId = jobId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Key)) {
                return false;
            }
            Key other = (Key) o;
            return jobId == other.jobId
                    && (uuid == null ? other.uuid == null : uuid.equals(other.uuid));
        }

        @Override
        public int hashCode() {
            return 31 * (uuid == null ? 0 : uuid.hashCode()) + jobId;
        }
    }

    /**
     * The live job buffers
     */
    private final ConcurrentMap<Key, JobBuffer> m_buffers
            = new ConcurrentHashMap<Key, JobBuffer>();

    /**
     * The buffer the current thread is producing into, so overlay reads on
     * a producer thread see that job's own buffered writes (read your
     * writes) without leaking other jobs' buffers
     */
    private final ThreadLocal<JobBuffer> m_producer = new ThreadLocal<JobBuffer>();

    /**
     * Global monotonic write sequence for cross chunk last-write ordering
     */
    private final AtomicLong m_writeSeq = new AtomicLong();

    /**
     * Round robin cursor kept between placer runs for job fairness
     */
    private int m_rrCursor;

    /**
     * Debug counters of the last drain pass (read by the block placer)
     */
    private long m_lastFlushedBlocks;
    private int m_lastFlushedChunks;
    private int m_lastCarriedChunks;
    private int m_lastStreamedChunks;
    private int m_lastWindowEvictions;

    /**
     * Region streamed flushing enabled (awe.engine.stream.enabled)
     */
    private volatile boolean m_streamEnabled = DEFAULT_STREAM_ENABLED;

    /**
     * Window watermark: a job holding more live section buffers than this
     * has its least-recently-written chunks streamed out
     * (awe.engine.stream.window-sections)
     */
    private volatile int m_windowSections = DEFAULT_WINDOW_SECTIONS;

    /**
     * Staleness: a chunk not written for this many placer runs is streamed
     * out (awe.engine.stream.stale-runs)
     */
    private volatile int m_staleRuns = DEFAULT_STALE_RUNS;

    /**
     * The placer run clock for the staleness readiness: advanced once per
     * non-force drain (one drain per placer run). Producers read it to
     * stamp chunks at write time.
     */
    private volatile int m_runCounter;

    /**
     * Default for {@link #m_streamEnabled}
     */
    public static final boolean DEFAULT_STREAM_ENABLED = true;

    /**
     * Default for {@link #m_windowSections}: 256 sections = 8 MB of live
     * buffers per job
     */
    public static final int DEFAULT_WINDOW_SECTIONS = 256;

    /**
     * Default for {@link #m_staleRuns}: ~2 seconds at interval 1
     */
    public static final int DEFAULT_STALE_RUNS = 40;

    JobBufferRegistry() {
    }

    /**
     * Apply the streaming configuration (awe.engine.stream). Called from
     * the block placer on startup and reload.
     *
     * @param enabled stream chunks of still-producing jobs
     * @param windowSections per job live section watermark (min 1)
     * @param staleRuns runs without a write before a chunk is ready (min 1)
     */
    public void configureStream(boolean enabled, int windowSections, int staleRuns) {
        m_streamEnabled = enabled;
        m_windowSections = Math.max(1, windowSections);
        m_staleRuns = Math.max(1, staleRuns);
    }

    /**
     * Buffer a batchable block write of a job. The caller has already
     * verified the block is batchable (plain id + data) and enforced the
     * disallowed-blocks blacklist.
     *
     * @return true when the block was buffered; false when the shared
     * section budget is full and a NEW section would be needed - the caller
     * must then place the block through the classic path
     */
    public boolean buffer(IPlayerEntry player, int jobId, World weWorld,
            IWorld aweWorld, IJobEntry job,
            int x, int y, int z, int id, int data, boolean notify) {
        if (aweWorld == null || y < 0 || y > 255) {
            return false;
        }

        final JobBuffer buf = getOrCreate(player, jobId, weWorld, aweWorld, job);
        //Publish the current producer buffer so this thread's overlay reads
        //resolve to its own job
        m_producer.set(buf);

        final int cx = SectionMath.blockToChunk(x);
        final int cz = SectionMath.blockToChunk(z);
        final long key = SectionMath.chunkKey(cx, cz);

        for (;;) {
            PendingChunk chunk = buf.getChunks().get(key);
            if (chunk == null) {
                //Single producer per job: publish + write while holding the
                //new chunk's lock so the drain (which removes under the same
                //lock) can never flush a half written chunk
                final PendingChunk created = new PendingChunk(cx, cz);
                synchronized (created) {
                    if (buf.getChunks().putIfAbsent(key, created) != null) {
                        //Lost a race (defensive - normally one producer):
                        //retry against the mapped chunk
                        continue;
                    }
                    buf.getChunkOrder().add(key);
                    return write(buf, created, x, y, z, id, data, notify);
                }
            }

            synchronized (chunk) {
                //Producer re-check: the drain removes the chunk from the map
                //before flushing it. If it is gone (or replaced), this write
                //lost the race and must retry into a fresh chunk buffer.
                if (buf.getChunks().get(key) != chunk) {
                    continue;
                }
                return write(buf, chunk, x, y, z, id, data, notify);
            }
        }
    }

    /**
     * Store the block into a locked, still-mapped chunk. Acquires a shared
     * section budget slot when a new section buffer is needed.
     */
    private boolean write(JobBuffer buf, PendingChunk chunk,
            int x, int y, int z, int id, int data, boolean notify) {
        final boolean needsNewSection = chunk.needsNewSection(y);
        if (needsNewSection && !SectionBudget.getShared().tryAcquire()) {
            //Budget full: the caller uses the classic path. An empty chunk
            //we just created is harmless - it holds no sections and the
            //drain/prune removes it.
            return false;
        }

        final int beforeCount = chunk.getCount();
        chunk.setBlock(x, y, z, id, data, notify, (int) m_writeSeq.getAndIncrement());
        //Staleness stamp for the streaming drain (we hold the chunk lock)
        chunk.setLastTouchRun(m_runCounter);

        if (needsNewSection) {
            buf.getSectionsHeld().incrementAndGet();
        }
        buf.getQueuedBlocks().addAndGet(chunk.getCount() - beforeCount);
        buf.getTotalBufferedCounter().incrementAndGet();
        return true;
    }

    /**
     * Resolve (or create) the buffer of a job
     */
    private JobBuffer getOrCreate(IPlayerEntry player, int jobId, World weWorld,
            IWorld aweWorld, IJobEntry job) {
        final UUID uuid = player == null ? null : player.getUUID();
        final Key key = new Key(uuid, jobId);

        JobBuffer buf = m_buffers.get(key);
        if (buf != null) {
            if (buf.getJob() == null && job != null) {
                buf.setJob(job);
            }
            return buf;
        }

        buf = new JobBuffer(player, jobId, aweWorld.getName(), weWorld, aweWorld, job);
        //Bind the job's undo capture sink now: in columnar mode the
        //producer registered it earlier in this very write's call stack
        //(the suppression seam runs upstream of the buffer), so the
        //binding is deterministic and a later reuse of the job id by
        //another session cannot redirect this buffer's flush captures
        buf.setCaptureSink(ColumnarUndoRegistry.get(uuid, jobId));
        JobBuffer prev = m_buffers.putIfAbsent(key, buf);
        return prev != null ? prev : buf;
    }

    /**
     * True when a live buffer exists for a job. Used by the block placer
     * to decide whether a removed job's capture sink can be unregistered
     * immediately (no buffer = no flush will ever need it) or must wait
     * for the buffer's prune hook.
     */
    public boolean hasBuffer(UUID uuid, int jobId) {
        return m_buffers.containsKey(new Key(uuid, jobId));
    }

    /**
     * Overlay read for the producer thread: the pending slot of a position
     * in this thread's own job buffer, {@link SectionMath#EMPTY_SLOT} when
     * nothing is buffered there. Consulted by {@link ChunkBatchWriter} so
     * buffered writes are visible to same-operation reads (read your
     * writes). Returns a miss on any non producer thread.
     */
    public int overlayGet(IWorld world, int x, int y, int z) {
        final JobBuffer buf = m_producer.get();
        if (buf == null || world == null
                || !buf.getWorldName().equals(world.getName())) {
            return SectionMath.EMPTY_SLOT;
        }

        final long key = SectionMath.chunkKey(
                SectionMath.blockToChunk(x), SectionMath.blockToChunk(z));
        final PendingChunk chunk = buf.getChunks().get(key);
        if (chunk == null) {
            return SectionMath.EMPTY_SLOT;
        }

        synchronized (chunk) {
            if (buf.getChunks().get(key) != chunk) {
                return SectionMath.EMPTY_SLOT;
            }
            return chunk.getPendingSlot(x, y, z);
        }
    }

    /**
     * Drop any buffered block at a position (a classic path write is about
     * to place a newer value there and must not be overwritten by the stale
     * buffered value on flush). Called on the main thread from
     * {@link ChunkBatchWriter#clearPending}; scans the world's buffers.
     */
    public void clearBuffered(IWorld world, int x, int y, int z) {
        if (world == null || m_buffers.isEmpty()) {
            return;
        }

        final long key = SectionMath.chunkKey(
                SectionMath.blockToChunk(x), SectionMath.blockToChunk(z));

        for (JobBuffer buf : m_buffers.values()) {
            if (!buf.getWorldName().equals(world.getName())) {
                continue;
            }
            final PendingChunk chunk = buf.getChunks().get(key);
            if (chunk == null) {
                continue;
            }
            synchronized (chunk) {
                if (buf.getChunks().get(key) != chunk) {
                    continue;
                }
                if (chunk.clear(x, y, z)) {
                    buf.getQueuedBlocks().decrementAndGet();
                }
            }
        }
    }

    /**
     * Drain the buffers round robin with the production flush sink, metered
     * by the flush limit (budget). Called by the block placer at window
     * close.
     */
    public void drainRoundRobin(ChunkBatchWriter.IFlushLimit limit) {
        drainRoundRobin(limit, PRODUCTION_SINK, false);
    }

    /**
     * Fully flush every buffer with the production sink, ignoring the budget
     * and readiness (shutdown / reload / demanding entries). Nothing is left
     * buffered.
     */
    public void forceFlush() {
        drainRoundRobin(null, PRODUCTION_SINK, true);
    }

    /**
     * Fully flush every buffer with an injected sink (test seam)
     */
    void forceFlush(IFlushSink sink) {
        drainRoundRobin(null, sink, true);
    }

    /**
     * One job's share of a drain pass: the buffer plus, for a
     * still-producing job serviced by the streaming readiness, the ordered
     * (least-recently-written first) chunk keys selected for this pass.
     * {@code streamKeys == null} means a full drain (job done or force):
     * the chunk order queue is polled instead.
     */
    private static final class JobDrain {

        final JobBuffer buf;
        final ArrayDeque<Long> streamKeys;

        JobDrain(JobBuffer buf, ArrayDeque<Long> streamKeys) {
            this.buf = buf;
            this.streamKeys = streamKeys;
        }
    }

    /**
     * Round robin drain.
     *
     * A job contributes chunks when it is ready - its producer has finished
     * (task done / job gone / no job) - or when {@code force} is set; in
     * addition, with streaming enabled, a still-producing job contributes
     * its watermark/staleness-ready chunks (see {@link #selectStreamReady}).
     * A canceled job's unflushed buffers are dropped, never placed
     * (already streamed blocks stay in the world and remain undoable
     * through the operation time change set). Ready jobs contribute one
     * chunk per rotation so no single job starves the others. The flush
     * stops when the budget is used up (at least one chunk is always
     * flushed) and unflushed chunks carry over to the next run - streamed
     * readiness is recomputed from the live watermark on every run, so a
     * budget-cut selection simply reappears while the job stays over the
     * window.
     *
     * Undo note: streaming flushes nothing that is not already captured -
     * the job's operation time change set recorded every old value at
     * produce time, before any flush.
     *
     * @param limit budget, null for an unmetered full flush
     * @param sink the flush sink
     * @param force flush even jobs that are still producing
     */
    void drainRoundRobin(ChunkBatchWriter.IFlushLimit limit, IFlushSink sink, boolean force) {
        m_lastFlushedBlocks = 0;
        m_lastFlushedChunks = 0;
        m_lastCarriedChunks = 0;
        m_lastStreamedChunks = 0;
        m_lastWindowEvictions = 0;

        if (!force) {
            //The staleness clock: one tick per placer-run drain
            m_runCounter++;
        }

        if (m_buffers.isEmpty()) {
            return;
        }

        final List<JobDrain> ready = new ArrayList<JobDrain>();
        for (JobBuffer buf : m_buffers.values()) {
            if (isCanceled(buf)) {
                //Cancel semantics: drop the unflushed buffers (parity with
                //the classic queue purge), never place them
                discardBuffer(buf);
                continue;
            }
            if (force || isReady(buf)) {
                ready.add(new JobDrain(buf, null));
            } else if (m_streamEnabled) {
                final ArrayDeque<Long> streamKeys = selectStreamReady(buf);
                if (streamKeys != null) {
                    ready.add(new JobDrain(buf, streamKeys));
                }
            }
        }
        if (ready.isEmpty()) {
            return;
        }

        boolean first = true;
        int idx = m_rrCursor;
        while (!ready.isEmpty()) {
            if (!first && limit != null && !limit.shouldContinue()) {
                break;
            }

            if (idx >= ready.size()) {
                idx = 0;
            }
            final JobDrain drain = ready.get(idx);

            final Long key = drain.streamKeys != null
                    ? drain.streamKeys.poll()
                    : drain.buf.getChunkOrder().poll();
            if (key == null) {
                //This job's share of the pass is drained; prune only a
                //fully drained job that finished producing
                ready.remove(idx);
                if (drain.streamKeys == null) {
                    pruneIfDone(drain.buf);
                }
                continue;
            }

            final PendingChunk chunk = detach(drain.buf, key.longValue());
            if (chunk == null) {
                //Duplicate order key / already drained (e.g. streamed out
                //in an earlier run) - skip without cost
                continue;
            }

            flushChunk(drain.buf, chunk, sink);
            if (drain.streamKeys != null) {
                m_lastStreamedChunks++;
            }
            first = false;
            idx++;
        }

        m_rrCursor = ready.isEmpty() ? 0 : idx;

        //Count what carried over for debug
        for (JobBuffer buf : m_buffers.values()) {
            m_lastCarriedChunks += buf.getChunks().size();
        }
    }

    /**
     * Streaming readiness of a still-producing job: the chunk keys to
     * flush this pass, in ascending last-write order, or null when nothing
     * is ready.
     *
     * <ul>
     * <li>Window watermark: when the job holds more live section buffers
     * than window-sections, its least-recently-written chunks are selected
     * until the job is down to HALF the window (hysteresis, see the low
     * watermark note in the body).</li>
     * <li>Staleness: a chunk not written for stale-runs placer runs is
     * selected regardless of the watermark.</li>
     * </ul>
     *
     * Attachment ordering stays safe across the many streamed passes of a
     * job: WorldEdit's reorder stage emits attachments (torches, levers,
     * rails) only after every support block of the operation, so an
     * attachment always carries a higher write sequence than every
     * support. Selection here is in ascending chunk last-write order, so a
     * support-bearing chunk is selected no later than the chunk holding
     * its attachment, and within one chunk flush the shouldPlaceLast
     * deferral (ChunkBatchWriter) replays attachments after the supports.
     * Residual cross-chunk cases (a support chunk rewritten later than the
     * attachment write) are covered exactly like the job-end drain of the
     * v1 engine: direct section writes fire no physics and the physics
     * freeze holds during classic replay, so an attachment cannot pop
     * before its support lands.
     */
    private ArrayDeque<Long> selectStreamReady(JobBuffer buf) {
        if (buf.getChunks().isEmpty()) {
            return null;
        }

        final int held = buf.getSectionsHeld().get();
        final int run = m_runCounter;

        //Snapshot the per chunk stamps under each chunk's lock
        final List<long[]> stamps = new ArrayList<long[]>(buf.getChunks().size());
        for (Map.Entry<Long, PendingChunk> entry : buf.getChunks().entrySet()) {
            final PendingChunk chunk = entry.getValue();
            synchronized (chunk) {
                if (buf.getChunks().get(entry.getKey()) != chunk) {
                    continue;
                }
                stamps.add(new long[]{
                    chunk.getLastWriteSeq(),
                    chunk.getSectionCount(),
                    chunk.getLastTouchRun(),
                    entry.getKey().longValue()});
            }
        }
        if (stamps.isEmpty()) {
            return null;
        }

        //Least recently written first
        Collections.sort(stamps, new Comparator<long[]>() {
            @Override
            public int compare(long[] a, long[] b) {
                return a[0] < b[0] ? -1 : (a[0] == b[0] ? 0 : 1);
            }
        });

        //Hysteresis: once over the window, evict down to HALF the window,
        //not just below it. WE's region iterators sweep horizontal layers
        //(y outermost - verified in CuboidRegion), so every chunk column
        //of a wide job is rewritten on every layer; evicting the bare
        //minimum each run would re-flush the same columns over and over
        //with one thin slice each (a refresh packet + relight per flush).
        //Draining to the low watermark makes eviction passes rarer and
        //lets a rewritten column accumulate full sections before it
        //streams again.
        int mustEvict = held > m_windowSections
                ? held - (m_windowSections / 2) : 0;

        ArrayDeque<Long> ready = null;
        for (long[] stamp : stamps) {
            final boolean windowEvict = mustEvict > 0;
            final boolean stale = run - (int) stamp[2] >= m_staleRuns;
            if (!windowEvict && !stale) {
                //Not over the window (any more) and not stale; keep
                //scanning - stale chunks are not ordered by seq
                continue;
            }

            if (ready == null) {
                ready = new ArrayDeque<Long>();
            }
            ready.add(Long.valueOf(stamp[3]));
            if (windowEvict) {
                mustEvict -= (int) stamp[1];
                m_lastWindowEvictions++;
            }
        }
        return ready;
    }

    /**
     * True when the job of a buffer was canceled: its unflushed buffers
     * are dropped instead of flushed
     */
    private static boolean isCanceled(JobBuffer buf) {
        final IJobEntry job = buf.getJob();
        return job != null && job.getStatus() == JobStatus.Canceled;
    }

    /**
     * Drop every unflushed chunk of a (canceled) buffer without placing
     * anything: release the shared section budget and the counters, then
     * prune the buffer. A producer racing the discard retries into a fresh
     * chunk (detach contract); the recreated buffer is discarded again on
     * the next drain once production stops.
     */
    private void discardBuffer(JobBuffer buf) {
        for (;;) {
            final Long key = buf.getChunkOrder().poll();
            if (key == null) {
                break;
            }
            discardChunk(buf, key.longValue());
        }
        //Defensive sweep: every mapped chunk had its key queued, but a
        //duplicate-free walk of the map costs nothing extra here
        for (Long key : buf.getChunks().keySet()) {
            discardChunk(buf, key.longValue());
        }

        final UUID uuid = buf.getPlayer() == null ? null : buf.getPlayer().getUUID();
        if (m_buffers.remove(new Key(uuid, buf.getJobId()), buf)) {
            ColumnarUndoRegistry.unregister(uuid, buf.getJobId());
            logJobDone(buf);
        }
    }

    /**
     * Detach and drop one chunk of a discarded buffer (no sink)
     */
    private void discardChunk(JobBuffer buf, long key) {
        final PendingChunk chunk = detach(buf, key);
        if (chunk == null) {
            return;
        }
        buf.getQueuedBlocks().addAndGet(-chunk.getCount());
        buf.getSectionsHeld().addAndGet(-chunk.getSectionCount());
        SectionBudget.getShared().release(chunk.getSectionCount());
    }

    /**
     * Flush one detached chunk and update the job counters + shared budget.
     * The job's undo capture sink (columnar mode) rides along so the sink
     * captures the pre-write old values; a job without one (changeset
     * mode, undo off, loose writes, undo replays) flushes with a null sink
     * at zero capture cost. The sink is the one BOUND to the buffer at its
     * creation - never a bare live registry lookup, which a reused job id
     * of a later session could redirect. The guarded registry fallback
     * below only fills a binding that was null at creation (a mid-job
     * switch into columnar mode) and only while the buffer's own job is
     * still producing: id reuse requires the old job to be dead, so a
     * still-live job's registry entry is necessarily its own.
     */
    private void flushChunk(JobBuffer buf, PendingChunk chunk, IFlushSink sink) {
        final int count = chunk.getCount();
        final int sections = chunk.getSectionCount();
        final UUID uuid = buf.getPlayer() == null ? null : buf.getPlayer().getUUID();
        ICaptureSink captureSink = buf.getCaptureSink();
        if (captureSink == null && buf.getJob() != null && !isReady(buf)) {
            captureSink = ColumnarUndoRegistry.get(uuid, buf.getJobId());
            if (captureSink != null) {
                buf.setCaptureSink(captureSink);
            }
        }
        try {
            sink.flush(buf.getWorld(), buf.getBukkitWorld(), chunk, captureSink);
        } catch (Throwable ex) {
            log("Error while flushing buffered chunk " + chunk.getX()
                    + "," + chunk.getZ() + ": " + ex);
        } finally {
            buf.getQueuedBlocks().addAndGet(-count);
            buf.getTotalFlushedCounter().addAndGet(count);
            buf.getSectionsHeld().addAndGet(-sections);
            SectionBudget.getShared().release(sections);
            m_lastFlushedBlocks += count;
            m_lastFlushedChunks++;
        }
    }

    /**
     * Remove a chunk from a buffer (under its lock, before flush) so a
     * racing producer retries into a fresh buffer
     */
    private PendingChunk detach(JobBuffer buf, long key) {
        final PendingChunk chunk = buf.getChunks().get(key);
        if (chunk == null) {
            return null;
        }
        synchronized (chunk) {
            if (buf.getChunks().get(key) == chunk) {
                buf.getChunks().remove(key);
                return chunk;
            }
            return null;
        }
    }

    /**
     * True when a job has finished producing and its buffered chunks may be
     * flushed
     */
    private static boolean isReady(JobBuffer buf) {
        final IJobEntry job = buf.getJob();
        if (job == null) {
            //Loose write with no registered job: flush as soon as drained
            return true;
        }
        if (job.isTaskDone()) {
            return true;
        }
        final JobStatus status = job.getStatus();
        return status == JobStatus.Done || status == JobStatus.Canceled;
    }

    /**
     * Prune an emptied buffer whose job is done
     */
    private void pruneIfDone(JobBuffer buf) {
        if (!buf.getChunks().isEmpty() || !buf.getChunkOrder().isEmpty()) {
            return;
        }
        if (!isReady(buf)) {
            return;
        }
        final UUID uuid = buf.getPlayer() == null ? null : buf.getPlayer().getUUID();
        if (m_buffers.remove(new Key(uuid, buf.getJobId()), buf)) {
            //The job is done and fully drained: no further flush can need
            //its capture sink. The columnar log itself stays attached to
            //the session's composite change set for the undo replay.
            ColumnarUndoRegistry.unregister(uuid, buf.getJobId());
            logJobDone(buf);
        }
    }

    /**
     * Fold a once-per-run server-global sample (used heap + TPS + budget
     * exceeded) into every live job buffer's IJobEntry. Called by the block
     * placer run loop while engine debug is on; buffers whose job is null
     * (loose writes) or not a {@link JobEntry} are skipped. See {@link
     * JobEntry#recordTelemetry} for the global-sample caveat.
     *
     * @param usedHeap used heap this run (bytes)
     * @param tpsMilli TPS estimate this run, encoded as tps*1000
     * @param budgetExceeded whether this run exhausted the tick budget
     */
    public void recordTelemetry(long usedHeap, long tpsMilli, boolean budgetExceeded) {
        for (JobBuffer buf : m_buffers.values()) {
            final IJobEntry job = buf.getJob();
            if (job instanceof JobEntry) {
                ((JobEntry) job).recordTelemetry(usedHeap, tpsMilli, budgetExceeded);
            }
        }
    }

    /**
     * Per job completion debug line (total blocks, wall ms, avg blocks/sec)
     * enriched with the buffered job's memory + smoothness telemetry. Loose
     * write buffers (no registered IJobEntry, e.g. the //undo replay's
     * jobId -1 writes) print nothing: such a buffer flushes and prunes
     * every drain, so a "completion" line per fragment is pure log spam -
     * their throughput shows in the per run [ENGINE] line instead. A real
     * job that was never sampled prints only the base line (blocks/wall/avg).
     */
    private static void logJobDone(JobBuffer buf) {
        if (!EngineDebug.isEnabled() || buf.getJob() == null) {
            return;
        }
        final long total = buf.getTotalFlushed();
        final long wallMs = System.currentTimeMillis() - buf.getStartMillis();
        final IJobEntry job = buf.getJob();
        if (job instanceof JobEntry) {
            final JobEntry je = (JobEntry) job;
            if (je.hasTelemetry()) {
                log(EngineStats.bufferedJobLine(buf.getJobId(), total, wallMs, true,
                        je.getPeakHeap(), je.getHeapDelta(), je.getMinTps(),
                        je.getBudgetExceeded()));
                return;
            }
        }
        log(EngineStats.bufferedJobLine(buf.getJobId(), total, wallMs));
    }

    /**
     * True when any buffer still holds chunks (used so the placer keeps
     * running while buffered work remains)
     */
    public boolean hasWork() {
        for (JobBuffer buf : m_buffers.values()) {
            if (!buf.getChunks().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Number of blocks buffered and not yet flushed for a player (progress
     * reporting truthfulness)
     */
    public int getBufferedCount(IPlayerEntry player) {
        if (player == null) {
            return 0;
        }
        final UUID uuid = player.getUUID();
        int total = 0;
        for (JobBuffer buf : m_buffers.values()) {
            final IPlayerEntry p = buf.getPlayer();
            final UUID u = p == null ? null : p.getUUID();
            if (uuid == null ? u == null : uuid.equals(u)) {
                total += buf.getQueuedCount();
            }
        }
        return total;
    }

    /**
     * Number of live job buffers
     */
    public int getBufferCount() {
        return m_buffers.size();
    }

    public long getLastFlushedBlocks() {
        return m_lastFlushedBlocks;
    }

    public int getLastFlushedChunks() {
        return m_lastFlushedChunks;
    }

    public int getLastCarriedChunks() {
        return m_lastCarriedChunks;
    }

    /**
     * Chunks flushed by the last drain out of still-producing jobs
     * (watermark or staleness readiness)
     */
    public int getLastStreamedChunks() {
        return m_lastStreamedChunks;
    }

    /**
     * Chunks selected by the window watermark in the last drain (staleness
     * only picks are counted in {@link #getLastStreamedChunks} but not
     * here)
     */
    public int getLastWindowEvictions() {
        return m_lastWindowEvictions;
    }

    /**
     * The current placer-run clock of the staleness readiness (test seam)
     */
    int getRunCounter() {
        return m_runCounter;
    }

    /**
     * The current global write sequence (number of buffered writes so far).
     * Test seam for the monotonic sequence assertion.
     */
    long getWriteSeq() {
        return m_writeSeq.get();
    }

    /**
     * Test seam: forget the current thread's producer buffer
     */
    void clearProducerThreadLocal() {
        m_producer.remove();
    }
}
