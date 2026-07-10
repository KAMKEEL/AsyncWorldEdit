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
package org.primesoft.asyncworldedit.blockPlacer.entries;

import com.sk89q.worldedit.util.eventbus.EventBus;
import org.primesoft.asyncworldedit.api.blockPlacer.entries.IJobEntry;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.primesoft.asyncworldedit.api.MessageSystem;
import org.primesoft.asyncworldedit.api.blockPlacer.IBlockPlacer;
import org.primesoft.asyncworldedit.blockPlacer.BlockPlacerEntry;
import org.primesoft.asyncworldedit.api.blockPlacer.IJobEntryListener;
import org.primesoft.asyncworldedit.api.blockPlacer.entries.JobStatus;
import org.primesoft.asyncworldedit.api.configuration.IPermissionGroup;
import org.primesoft.asyncworldedit.api.playerManager.IPlayerEntry;
import org.primesoft.asyncworldedit.api.worldedit.ICancelabeEditSession;
import org.primesoft.asyncworldedit.core.AwePlatform;
import org.primesoft.asyncworldedit.events.JobDoneEvent;
import org.primesoft.asyncworldedit.events.JobStateChangedEvent;
import org.primesoft.asyncworldedit.strings.MessageType;

/**
 * Job description empty
 *
 * @author SBPrime
 */
public class JobEntry extends BlockPlacerEntry implements IJobEntry {
    /**
     * Job name
     */
    private final String m_name;

    /**
     * Is the job status
     */
    private JobStatus m_status;

    /**
     * Cancelable edit session
     */
    private final ICancelabeEditSession m_cEditSession;

    /**
     * The player name
     */
    private final IPlayerEntry m_player;

    /**
     * Is the async task done
     */
    private boolean m_taskDone;

    /**
     * All job state changed events
     */
    private final List<IJobEntryListener> m_jobStateChanged;
    
    /**
     * The event bus
     */
    private final EventBus m_eventBus;

    /**
     * Wall clock time (ms) when the job was created, used by the classic
     * engine completion debug line. Mirrors the buffered engine, which stamps
     * its start when the first buffered write registers the job.
     */
    private final long m_startMillis = System.currentTimeMillis();

    /**
     * Blocks placed by the classic engine for this job (only incremented when
     * engine debug is on; the buffered engine keeps its own count).
     */
    private final AtomicLong m_blocksPlaced = new AtomicLong();

    /**
     * Guards the one-shot classic completion debug line so it prints at most
     * once regardless of how many times the job is removed.
     */
    private final AtomicBoolean m_completionLogged = new AtomicBoolean();

    /**
     * Buffer-first engine memory + smoothness telemetry, only sampled while
     * engine debug is on (zero cost otherwise). Both engines fold the same
     * per run server sample into these fields (classic samples its active
     * JobEntry directly, buffered attributes the sample to the JobBuffer's
     * IJobEntry) so both completion lines read the identical fields.
     *
     * IMPORTANT: heap and TPS are SERVER-GLOBAL samples taken during the job's
     * lifetime, NOT isolated to this job. They are meaningful for the single
     * active job A/B case (one builder pasting); with multiple concurrent jobs
     * the numbers overlap - do not read them as per-job-exclusive.
     */
    private final AtomicLong m_peakHeap = new AtomicLong();

    /**
     * Used heap sampled at the job's first sampled run (heap-delta baseline)
     */
    private volatile long m_startHeap;

    /**
     * Guards the one-shot capture of {@link #m_startHeap}; also doubles as the
     * "this job has at least one sample" flag.
     */
    private final AtomicBoolean m_startHeapSet = new AtomicBoolean();

    /**
     * Minimum TPS estimate seen during the job, encoded as tps*1000 so it can
     * be folded lock free with an atomic min (seeded to MAX_VALUE = no sample)
     */
    private final AtomicLong m_minTpsMilli = new AtomicLong(Long.MAX_VALUE);

    /**
     * Number of sampled runs during the job that exceeded the tick budget
     */
    private final AtomicInteger m_budgetExceeded = new AtomicInteger();

    /**
     * Cumulative JVM GC collection count at the job's first sampled run (the
     * gc-delta baseline). Like the heap fields these are SERVER-GLOBAL (all
     * collectors of the whole JVM), not isolated to this job.
     */
    private volatile long m_startGcCount;

    /**
     * Cumulative JVM GC time (ms) at the job's first sampled run
     */
    private volatile long m_startGcTimeMs;

    /**
     * Cumulative JVM GC collection count at the job's latest sampled run
     */
    private volatile long m_lastGcCount;

    /**
     * Cumulative JVM GC time (ms) at the job's latest sampled run
     */
    private volatile long m_lastGcTimeMs;

    /**
     * Fold one per run server-global sample into this job's telemetry. Called
     * once per placer run for every active job while engine debug is on.
     *
     * @param usedHeap used heap this run (bytes)
     * @param tpsMilli TPS estimate this run, encoded as tps*1000
     * @param budgetExceeded whether this run exhausted the tick budget
     * @param gcCount cumulative GC collections of the JVM (all collectors)
     * @param gcTimeMs cumulative GC time of the JVM in ms (all collectors)
     */
    public void recordTelemetry(long usedHeap, long tpsMilli, boolean budgetExceeded,
            long gcCount, long gcTimeMs) {
        //First sample sets the heap-delta + gc-delta baselines
        if (m_startHeapSet.compareAndSet(false, true)) {
            m_startHeap = usedHeap;
            m_startGcCount = gcCount;
            m_startGcTimeMs = gcTimeMs;
        }
        //The GC counters are monotonic, so the latest sample is the maximum;
        //samples arrive from the single placer thread (plain volatile writes)
        m_lastGcCount = gcCount;
        m_lastGcTimeMs = gcTimeMs;
        //peak = max used heap (lock free)
        for (;;) {
            final long peak = m_peakHeap.get();
            if (usedHeap <= peak) {
                break;
            }
            if (m_peakHeap.compareAndSet(peak, usedHeap)) {
                break;
            }
        }
        //min tps (lock free)
        for (;;) {
            final long min = m_minTpsMilli.get();
            if (tpsMilli >= min) {
                break;
            }
            if (m_minTpsMilli.compareAndSet(min, tpsMilli)) {
                break;
            }
        }
        if (budgetExceeded) {
            m_budgetExceeded.incrementAndGet();
        }
    }

    /**
     * Whether at least one per run sample was folded into this job (so the
     * completion line knows whether the telemetry fields are meaningful).
     *
     * @return true once {@link #recordTelemetry} ran at least once
     */
    public boolean hasTelemetry() {
        return m_startHeapSet.get();
    }

    /**
     * Peak used heap seen during the job (bytes).
     *
     * @return the peak, 0 when never sampled
     */
    public long getPeakHeap() {
        return m_peakHeap.get();
    }

    /**
     * Peak used heap minus the used heap at the job's first sample (bytes),
     * i.e. the heap growth attributable to the job's lifetime.
     *
     * @return the signed delta, 0 when never sampled
     */
    public long getHeapDelta() {
        return m_peakHeap.get() - m_startHeap;
    }

    /**
     * Minimum TPS estimate seen during the job.
     *
     * @return the minimum TPS, 0 when never sampled
     */
    public double getMinTps() {
        final long min = m_minTpsMilli.get();
        return min == Long.MAX_VALUE ? 0.0 : min / 1000.0;
    }

    /**
     * Number of sampled runs that exceeded the tick budget during the job.
     *
     * @return the budget-exceeded count
     */
    public int getBudgetExceeded() {
        return m_budgetExceeded.get();
    }

    /**
     * GC collections during the job: latest sampled cumulative count minus
     * the count at the job's first sample. SERVER-GLOBAL (see the field note).
     *
     * @return the collection count delta, 0 when never sampled
     */
    public long getGcCount() {
        return m_lastGcCount - m_startGcCount;
    }

    /**
     * GC time in milliseconds during the job: latest sampled cumulative time
     * minus the time at the job's first sample. SERVER-GLOBAL.
     *
     * @return the GC time delta in ms, 0 when never sampled
     */
    public long getGcTimeMs() {
        return m_lastGcTimeMs - m_startGcTimeMs;
    }

    /**
     * Wall clock time (ms) when this job was created.
     *
     * @return the creation timestamp
     */
    public long getStartMillis() {
        return m_startMillis;
    }

    /**
     * Record classic engine block placements for this job.
     *
     * @param count number of blocks placed
     */
    public void addBlocksPlaced(long count) {
        m_blocksPlaced.addAndGet(count);
    }

    /**
     * Total classic engine blocks placed for this job.
     *
     * @return the running block count
     */
    public long getBlocksPlaced() {
        return m_blocksPlaced.get();
    }

    /**
     * Claim the single classic completion log slot.
     *
     * @return true exactly once (for the first caller), false afterwards
     */
    public boolean claimCompletionLog() {
        return m_completionLogged.compareAndSet(false, true);
    }

    /**
     * Get the player UUID
     *
     * @return
     */
    @Override
    public IPlayerEntry getPlayer() {
        return m_player;
    }

    /**
     * Create new instance of the class
     *
     * @param player player uuid
     * @param jobId job id
     * @param name operation name
     */
    public JobEntry(IPlayerEntry player, int jobId, String name) {
        super(jobId, false);
        m_player = player;
        m_name = name;
        m_status = JobStatus.Initializing;
        m_cEditSession = null;
        m_jobStateChanged = new ArrayList<IJobEntryListener>();
        
        m_eventBus = AwePlatform.getInstance().getCore().getEventBus();
    }

    /**
     * Create new instance of the class
     *
     * @param player player uuid
     * @param jobId job id
     * @param name operation name
     * @param cEditSession the cancelable edit session
     */
    public JobEntry(IPlayerEntry player,
            ICancelabeEditSession cEditSession,
            int jobId, String name) {
        super(jobId, false);

        m_player = player;
        m_name = name;
        m_status = JobStatus.Initializing;
        m_cEditSession = cEditSession;
        m_jobStateChanged = new ArrayList<IJobEntryListener>();
        
        m_eventBus = AwePlatform.getInstance().getCore().getEventBus();
    }

    /**
     * Add job state change listener
     *
     * @param listener
     */
    @Override
    public void addStateChangedListener(IJobEntryListener listener) {
        if (listener == null) {
            return;
        }

        synchronized (m_jobStateChanged) {
            if (!m_jobStateChanged.contains(listener)) {
                m_jobStateChanged.add(listener);
            }
        }
    }

    /**
     * Remove the change state listener
     *
     * @param listener
     */
    @Override
    public void removeStateChangedListener(IJobEntryListener listener) {
        if (listener == null) {
            return;
        }

        synchronized (m_jobStateChanged) {
            if (m_jobStateChanged.contains(listener)) {
                m_jobStateChanged.remove(listener);
            }
        }
    }

    /**
     * Is the async task done
     *
     * @return
     */
    @Override
    public boolean isTaskDone() {
        return m_taskDone;
    }

    /**
     * Async task has finished
     */
    @Override
    public void taskDone() {
        m_taskDone = true;

        callStateChangedEvents();
        
        m_eventBus.post(new JobDoneEvent(this));
    }

    /**
     * Is the job started
     *
     * @return
     */
    @Override
    public JobStatus getStatus() {
        return m_status;
    }

    /**
     * Get the operation name
     *
     * @return
     */
    @Override
    public String getName() {
        return m_name;
    }

    /**
     * Set the job state
     *
     * @param newStatus
     */
    @Override
    public void setStatus(JobStatus newStatus) {
        JobStatus oldStatus = m_status;
        
        int newS = newStatus.getSeqNumber();
        int oldS = oldStatus.getSeqNumber();

        if (newS < oldS) {
            return;
        }
        m_status = newStatus;
        callStateChangedEvents();
        
        m_eventBus.post(new JobStateChangedEvent(this, oldStatus, newStatus));
    }

    /**
     * Cancel the job
     */
    @Override
    public void cancel() {
        JobStatus status = getStatus();
        if (status != JobStatus.Done && !m_taskDone) {
            setStatus(JobStatus.Canceled);
        }
        if (m_cEditSession != null) {
            m_cEditSession.cancel();
        }
    }

    /**
     * Convert job status to string
     *
     * @return
     */
    @Override
    public String getStatusString() {
        switch (m_status) {
            case Done:
                return MessageType.CMD_JOBS_STATUS_DONE.format();
            case Canceled:
                return MessageType.CMD_JOBS_STATUS_CANCELED.format();
            case Initializing:
                return MessageType.CMD_JOBS_STATUS_INITIALIZING.format();
            case PlacingBlocks:
                return MessageType.CMD_JOBS_STATUS_PLACING_BLOCKS.format();
            case Preparing:
                return MessageType.CMD_JOBS_STATUS_PREPARING.format();
            case Waiting:
                return MessageType.CMD_JOBS_STATUS_WAITING.format();
        }

        return "";
    }

    @Override
    public String toString() {
        return MessageType.CMD_JOBS_FORMAT.format(getJobId(), getName());
    }

    @Override
    public boolean process(IBlockPlacer bp) {
        final IPlayerEntry player = m_player;

        switch (m_status) {
            case Canceled:
            case Done:
                bp.removeJob(player, this);
                return true;
            case PlacingBlocks:
                setStatus(JobStatus.Done);
                bp.removeJob(player, this);
                break;
            case Initializing:
            case Preparing:
            case Waiting:
                setStatus(JobStatus.PlacingBlocks);
                break;
        }

        IPermissionGroup group = player.getPermissionGroup();
        if (player.getMessaging(MessageSystem.TALKATIVE)) {
            player.say(MessageType.CMD_JOBS_STATUS.format(toString(), getStatusString()));
        }

        return true;
    }

    /**
     * Inform the listener of state changed
     */
    private void callStateChangedEvents() {
        synchronized (m_jobStateChanged) {
            for (IJobEntryListener listener : m_jobStateChanged) {
                listener.jobStateChanged(this);
            }
        }
    }
}
