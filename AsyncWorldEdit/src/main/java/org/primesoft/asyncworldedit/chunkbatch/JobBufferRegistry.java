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
import java.util.ArrayList;
import java.util.List;
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
 * <li>Buffered chunks flush only when their job has finished producing (or
 * on force), classic queue entries are placed during the placer run. When a
 * block does not fit the buffer (section budget full) the producer falls
 * back to the classic path; that classic write clears any earlier buffered
 * value at the same position ({@link #clearBuffered}), so the newer classic
 * write is not overwritten by the stale buffered value at flush time.</li>
 * <li>A later buffered write to a position previously written classically
 * simply flushes after the classic write, so last-queued-wins holds in both
 * directions.</li>
 * <li>Within a job a global monotonic write sequence orders the last write
 * of every position so the classic replay of sub threshold chunks keeps
 * WorldEdit's support-before-attachment order.</li>
 * </ul>
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
         */
        void flush(World weWorld, IWorld aweWorld, PendingChunk chunk);
    }

    /**
     * The production flush sink: hand the chunk to the shared chunk batch
     * writer so the NMS direct write path and its classic replay are reused
     */
    private static final IFlushSink PRODUCTION_SINK = new IFlushSink() {
        @Override
        public void flush(World weWorld, IWorld aweWorld, PendingChunk chunk) {
            ChunkBatchWriter.getInstance().flushJobChunk(weWorld, aweWorld, chunk);
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

    JobBufferRegistry() {
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
        JobBuffer prev = m_buffers.putIfAbsent(key, buf);
        return prev != null ? prev : buf;
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
     * Round robin drain.
     *
     * A job is flushed only when it is ready - its producer has finished
     * (task done / job gone / no job) - or when {@code force} is set;
     * partial chunks of a still-producing job stay buffered until job end or
     * force. Ready jobs contribute one chunk per rotation so no single job
     * starves the others. The flush stops when the budget is used up (at
     * least one chunk is always flushed) and unflushed chunks carry over to
     * the next run.
     *
     * @param limit budget, null for an unmetered full flush
     * @param sink the flush sink
     * @param force flush even jobs that are still producing
     */
    void drainRoundRobin(ChunkBatchWriter.IFlushLimit limit, IFlushSink sink, boolean force) {
        m_lastFlushedBlocks = 0;
        m_lastFlushedChunks = 0;
        m_lastCarriedChunks = 0;

        if (m_buffers.isEmpty()) {
            return;
        }

        final List<JobBuffer> ready = new ArrayList<JobBuffer>();
        for (JobBuffer buf : m_buffers.values()) {
            if (force || isReady(buf)) {
                ready.add(buf);
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
            final JobBuffer buf = ready.get(idx);

            final Long key = buf.getChunkOrder().poll();
            if (key == null) {
                //This job is drained: prune it if it finished producing
                ready.remove(idx);
                pruneIfDone(buf);
                continue;
            }

            final PendingChunk chunk = detach(buf, key.longValue());
            if (chunk == null) {
                //Duplicate order key / already drained - skip without cost
                continue;
            }

            flushChunk(buf, chunk, sink);
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
     * Flush one detached chunk and update the job counters + shared budget
     */
    private void flushChunk(JobBuffer buf, PendingChunk chunk, IFlushSink sink) {
        final int count = chunk.getCount();
        final int sections = chunk.getSectionCount();
        try {
            sink.flush(buf.getWorld(), buf.getBukkitWorld(), chunk);
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
     * enriched with the buffered job's memory + smoothness telemetry. When the
     * buffer's IJobEntry is null (loose writes) or was never sampled, only the
     * base line prints (blocks/wall/avg).
     */
    private static void logJobDone(JobBuffer buf) {
        if (!EngineDebug.isEnabled()) {
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
