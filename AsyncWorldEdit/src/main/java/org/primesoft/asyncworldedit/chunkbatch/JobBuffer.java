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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.primesoft.asyncworldedit.api.IWorld;
import org.primesoft.asyncworldedit.api.blockPlacer.entries.IJobEntry;
import org.primesoft.asyncworldedit.api.playerManager.IPlayerEntry;

/**
 * The buffered block writes of one async job in one world: chunk keyed
 * {@link PendingChunk} buffers filled directly by the job's producer
 * thread and drained by the block placer on the main thread.
 *
 * Concurrency contract (enforced by {@link JobBufferRegistry}):
 * <ul>
 * <li>the chunk map and chunk order queue are concurrent collections,</li>
 * <li>every access to a {@link PendingChunk} (producer write, overlay
 * read, clear, drain flush) happens inside {@code synchronized (chunk)},</li>
 * <li>a producer must re-check that the chunk is still mapped inside the
 * synchronized block; the drain removes a chunk from the map before it
 * flushes it, so a write that lost the race retries into a fresh chunk
 * buffer instead of being silently dropped.</li>
 * </ul>
 *
 * @author KAMKEEL
 */
public final class JobBuffer {

    private final IPlayerEntry m_player;

    private final int m_jobId;

    private final String m_worldName;

    /**
     * The WorldEdit world used for classic replay of small chunks
     */
    private final World m_weWorld;

    /**
     * The AWE world (resolves to the Bukkit world for direct writes)
     */
    private final IWorld m_bukkitWorld;

    /**
     * The job entry, null when the job could not be resolved (loose
     * writes with a job id but no registered job - those flush
     * immediately)
     */
    private volatile IJobEntry m_job;

    /**
     * Pending chunk buffers
     */
    private final ConcurrentMap<Long, PendingChunk> m_chunks
            = new ConcurrentHashMap<Long, PendingChunk>();

    /**
     * Chunk keys in first-touch order (drain order)
     */
    private final ConcurrentLinkedQueue<Long> m_chunkOrder
            = new ConcurrentLinkedQueue<Long>();

    /**
     * Number of pending (not yet flushed) blocks
     */
    private final AtomicInteger m_queuedBlocks = new AtomicInteger();

    /**
     * Number of section buffers currently held against the shared budget
     */
    private final AtomicInteger m_sectionsHeld = new AtomicInteger();

    /**
     * Total number of buffered writes (including same slot overwrites)
     */
    private final AtomicLong m_totalBuffered = new AtomicLong();

    /**
     * Total number of blocks flushed to the world
     */
    private final AtomicLong m_totalFlushed = new AtomicLong();

    /**
     * Wall clock time of the first buffered write
     */
    private final long m_startMillis;

    JobBuffer(IPlayerEntry player, int jobId, String worldName,
            World weWorld, IWorld bukkitWorld, IJobEntry job) {
        m_player = player;
        m_jobId = jobId;
        m_worldName = worldName;
        m_weWorld = weWorld;
        m_bukkitWorld = bukkitWorld;
        m_job = job;
        m_startMillis = System.currentTimeMillis();
    }

    public IPlayerEntry getPlayer() {
        return m_player;
    }

    public int getJobId() {
        return m_jobId;
    }

    public String getWorldName() {
        return m_worldName;
    }

    /**
     * The WorldEdit world for classic replay
     */
    public World getWorld() {
        return m_weWorld;
    }

    /**
     * The AWE world for the direct chunk writes
     */
    public IWorld getBukkitWorld() {
        return m_bukkitWorld;
    }

    /**
     * The job entry, may be null
     */
    public IJobEntry getJob() {
        return m_job;
    }

    void setJob(IJobEntry job) {
        m_job = job;
    }

    /**
     * Number of blocks buffered and not yet flushed
     */
    public int getQueuedCount() {
        return m_queuedBlocks.get();
    }

    /**
     * Total number of buffered writes over the job's lifetime (including
     * overwrites of the same position)
     */
    public long getTotalBuffered() {
        return m_totalBuffered.get();
    }

    /**
     * Total number of blocks flushed to the world so far
     */
    public long getTotalFlushed() {
        return m_totalFlushed.get();
    }

    /**
     * Wall clock time of the buffer creation (first buffered write)
     */
    public long getStartMillis() {
        return m_startMillis;
    }

    /**
     * Number of pending chunk buffers
     */
    public int getChunkCount() {
        return m_chunks.size();
    }

    ConcurrentMap<Long, PendingChunk> getChunks() {
        return m_chunks;
    }

    ConcurrentLinkedQueue<Long> getChunkOrder() {
        return m_chunkOrder;
    }

    AtomicInteger getQueuedBlocks() {
        return m_queuedBlocks;
    }

    AtomicInteger getSectionsHeld() {
        return m_sectionsHeld;
    }

    AtomicLong getTotalBufferedCounter() {
        return m_totalBuffered;
    }

    AtomicLong getTotalFlushedCounter() {
        return m_totalFlushed;
    }
}
