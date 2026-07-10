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
package org.primesoft.asyncworldedit.blockPlacer;

/**
 * Adaptive per-tick time budget for the block placer.
 *
 * The caller reports the start of every placer run through
 * {@link #onTickStart(long)} (timestamps come from the caller, so tests can
 * drive this class with a fake clock). The class keeps a rolling window of the
 * recent run timestamps to estimate the current server TPS and derives a time
 * allowance for the current run:
 *
 * - when the TPS estimate is at or above the target the base budget is
 *   granted, plus a share of the configured idle extra time that grows as the
 *   server gets closer to full speed (nothing else is loading the server),
 * - when the TPS estimate drops below the target the budget shrinks
 *   proportionally,
 * - the budget never drops below the configured floor so queued jobs always
 *   make some progress.
 *
 * This class is not thread safe, it is intended to be used from the block
 * placer task only.
 *
 * @author SBPrime
 */
public class AdaptiveTickBudget {

    /**
     * Full speed of a minecraft server (ticks per second)
     */
    public static final double MAX_TPS = 20.0;

    /**
     * Nanoseconds in a millisecond
     */
    private static final long NANOS_IN_MS = 1000000L;

    /**
     * Nanoseconds in a second
     */
    private static final double NANOS_IN_S = 1.0e9;

    /**
     * The TPS the budget tries to keep the server at
     */
    private final double m_targetTps;

    /**
     * Base time allowance granted at healthy TPS (nanoseconds)
     */
    private final long m_baseBudgetNanos;

    /**
     * Extra time allowance granted when the server is idle (nanoseconds)
     */
    private final long m_idleExtraNanos;

    /**
     * Minimum time allowance (nanoseconds), so progress never stalls
     */
    private final long m_minBudgetNanos;

    /**
     * Number of server ticks between two placer runs
     */
    private final double m_ticksPerRun;

    /**
     * Rolling window of run start timestamps (ring buffer)
     */
    private final long[] m_tickStarts;

    /**
     * Number of valid entries in the ring buffer
     */
    private int m_count;

    /**
     * Ring buffer position for the next write
     */
    private int m_head;

    /**
     * Last computed TPS estimate
     */
    private double m_tpsEstimate;

    /**
     * The time allowance for the current tick (nanoseconds)
     */
    private long m_budgetNanos;

    /**
     * Create a new adaptive tick budget
     *
     * @param targetTps target ticks per second (clamped to (0, 20])
     * @param baseBudgetMs base time allowance at healthy TPS (milliseconds)
     * @param idleExtraMs extra time allowance on an idle server (milliseconds)
     * @param minBudgetMs minimum time allowance (milliseconds)
     * @param ticksPerRun number of server ticks between placer runs (>= 1)
     * @param windowSize number of run timestamps kept for the TPS estimate
     */
    public AdaptiveTickBudget(double targetTps,
            long baseBudgetMs, long idleExtraMs, long minBudgetMs,
            long ticksPerRun, int windowSize) {
        m_targetTps = Math.min(MAX_TPS, targetTps > 0 ? targetTps : MAX_TPS);
        m_baseBudgetNanos = Math.max(0, baseBudgetMs) * NANOS_IN_MS;
        m_idleExtraNanos = Math.max(0, idleExtraMs) * NANOS_IN_MS;
        m_minBudgetNanos = Math.min(Math.max(0, minBudgetMs) * NANOS_IN_MS, m_baseBudgetNanos);
        m_ticksPerRun = Math.max(1, ticksPerRun);
        m_tickStarts = new long[Math.max(2, windowSize)];
        m_count = 0;
        m_head = 0;
        m_tpsEstimate = m_targetTps;
        m_budgetNanos = m_baseBudgetNanos;
    }

    /**
     * Report the start of a placer run, update the TPS estimate and compute
     * the time allowance for this run.
     *
     * @param nowNanos current time (nanoseconds, monotonic)
     * @return the time allowance for this run (nanoseconds)
     */
    public long onTickStart(long nowNanos) {
        m_tickStarts[m_head] = nowNanos;
        m_head = (m_head + 1) % m_tickStarts.length;
        if (m_count < m_tickStarts.length) {
            m_count++;
        }

        if (m_count < 2) {
            //Not enough samples yet: assume the server is healthy
            m_tpsEstimate = m_targetTps;
        } else {
            //The oldest sample: when the buffer is full m_head already points
            //at it, otherwise the buffer starts at 0
            int oldestIdx = (m_count < m_tickStarts.length) ? 0 : m_head;
            long spanNanos = nowNanos - m_tickStarts[oldestIdx];

            if (spanNanos <= 0) {
                m_tpsEstimate = MAX_TPS;
            } else {
                double ticksElapsed = (m_count - 1) * m_ticksPerRun;
                //After a long stall the scheduler fires the missed runs back
                //to back, so the window spans less wall time than ticksPerRun
                //implies and the raw estimate overshoots the physical 20 TPS
                //ceiling. Clamp to MAX_TPS: this is budget-neutral, because
                //computeBudget already caps the idle fraction at 1.0 for any
                //estimate at or above the target - the clamp only keeps the
                //reported estimate (debug lines, minTPS telemetry) honest.
                m_tpsEstimate = Math.min(MAX_TPS,
                        ticksElapsed / (spanNanos / NANOS_IN_S));
            }
        }

        m_budgetNanos = computeBudget(m_tpsEstimate);
        return m_budgetNanos;
    }

    /**
     * Compute the time allowance for the provided TPS estimate
     *
     * @param tps the TPS estimate
     * @return the time allowance (nanoseconds)
     */
    private long computeBudget(double tps) {
        if (tps >= m_targetTps) {
            //Healthy server: base budget plus a share of the idle extra time
            //that grows as the server approaches full speed
            double headroom = MAX_TPS - m_targetTps;
            double idleFraction = headroom <= 0
                    ? 1.0
                    : Math.min(1.0, (tps - m_targetTps) / headroom);

            return m_baseBudgetNanos + Math.round(idleFraction * m_idleExtraNanos);
        }

        //Lagging server: shrink the budget proportionally, but never below
        //the floor
        long budget = Math.round(m_baseBudgetNanos * (tps / m_targetTps));
        return Math.max(m_minBudgetNanos, budget);
    }

    /**
     * Check if the placer is still allowed to process entries in this run
     *
     * @param elapsedNanos time spent in the current run (nanoseconds)
     * @return true if there is time allowance left
     */
    public boolean shouldContinue(long elapsedNanos) {
        return elapsedNanos < m_budgetNanos;
    }

    /**
     * Get the time allowance computed for the current run
     *
     * @return the time allowance (nanoseconds)
     */
    public long getBudgetNanos() {
        return m_budgetNanos;
    }

    /**
     * Get the rolling TPS estimate
     *
     * @return estimated ticks per second
     */
    public double getTpsEstimate() {
        return m_tpsEstimate;
    }
}
