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

/**
 * Shared math and formatting for the per job completion debug lines so the
 * buffered and classic engines print comparable, byte-for-byte aligned output
 * (only the engine label differs). Kept side effect free so it can be unit
 * tested without touching the block placer.
 *
 * @author KAMKEEL
 */
public final class EngineStats {

    private EngineStats() {
    }

    /**
     * Average placement throughput in blocks per second. Guards against a
     * zero (or negative) wall time so a sub millisecond job never divides by
     * zero - it simply reports 0.
     *
     * @param blocks number of blocks placed
     * @param wallMs elapsed wall time in milliseconds
     * @return blocks/sec, or 0 when wallMs <= 0
     */
    public static double avgBlocksPerSec(long blocks, long wallMs) {
        if (wallMs <= 0) {
            return 0.0;
        }
        return blocks * 1000.0 / wallMs;
    }

    /**
     * Bytes rounded to whole megabytes with a trailing unit, e.g. {@code
     * 512MB}. 1 MB == 1024*1024 bytes.
     *
     * @param bytes a byte count
     * @return the rounded "NNMB" string
     */
    public static String formatMb(long bytes) {
        return mb(bytes) + "MB";
    }

    /**
     * Bytes rounded to whole megabytes, always signed, e.g. {@code +38MB} or
     * {@code -5MB} (zero is rendered {@code +0MB}). Used for the heap delta of
     * a job (peak used heap minus the used heap at job start).
     *
     * @param bytes a signed byte delta
     * @return the signed "+NNMB"/"-NNMB" string
     */
    public static String formatMbDelta(long bytes) {
        final long mb = mb(bytes);
        return (mb >= 0 ? "+" : "") + mb + "MB";
    }

    /**
     * Round a byte count to whole megabytes (banker-free half-up rounding).
     */
    private static long mb(long bytes) {
        return Math.round(bytes / (1024.0 * 1024.0));
    }

    /**
     * Fold a TPS reading into the lock-free milli-TPS accumulator encoding
     * (tps*1000 as a long) so the minimum over a job can be tracked with an
     * atomic min. Pure so the folding math is unit testable.
     *
     * @param tps a ticks-per-second reading
     * @return the reading encoded as {@code round(tps*1000)}
     */
    public static long tpsToMilli(double tps) {
        return Math.round(tps * 1000.0);
    }

    /**
     * Minimum of a milli-TPS accumulator and a new TPS reading (encoded).
     *
     * @param currentMinMilli the running minimum (milli-TPS)
     * @param tps a new ticks-per-second reading
     * @return the smaller of the two, in milli-TPS
     */
    public static long minTpsMilli(long currentMinMilli, double tps) {
        return Math.min(currentMinMilli, tpsToMilli(tps));
    }

    /**
     * Build a per job completion line. The wall time is floored to 1ms (like
     * the buffered engine) so avg is always finite.
     *
     * @param engine engine label ("buffered" or "classic")
     * @param jobId the job id
     * @param blocks number of blocks placed
     * @param wallMs elapsed wall time in milliseconds
     * @return the formatted "[ENGINE] job ... done" line
     */
    public static String jobLine(String engine, int jobId, long blocks, long wallMs) {
        final long safeWall = Math.max(1, wallMs);
        return String.format(
                "[ENGINE] job %d done: %s blocks=%d wall=%dms avg=%.0f blocks/sec",
                jobId, engine, blocks, safeWall, avgBlocksPerSec(blocks, safeWall));
    }

    /**
     * Build a per job completion line enriched with the memory and smoothness
     * telemetry sampled during the job. When {@code hasSamples} is false (the
     * job finished with no run sampled, e.g. debug toggled mid job) only the
     * base line is returned so the extra fields never print misleading zeros.
     *
     * IMPORTANT: heap-peak/heap-delta/minTPS/gc are SERVER-GLOBAL samples
     * taken during the job's lifetime, not isolated to this job - meaningful
     * for the single-active-job A/B (one builder pasting); with concurrent
     * jobs they overlap.
     *
     * @param engine engine label ("buffered" or "classic")
     * @param jobId the job id
     * @param blocks number of blocks placed
     * @param wallMs elapsed wall time in milliseconds
     * @param hasSamples whether any per run sample was folded into this job
     * @param peakHeapBytes max used heap seen during the job (bytes)
     * @param heapDeltaBytes peak used heap minus used heap at job start (bytes)
     * @param minTps minimum TPS estimate seen during the job
     * @param budgetExceeded number of sampled runs that exceeded the tick budget
     * @param gcCount GC collections during the job (delta over all collectors)
     * @param gcTimeMs GC time in ms during the job (delta over all collectors)
     * @return the formatted completion line, with the telemetry fields appended
     */
    public static String jobLine(String engine, int jobId, long blocks, long wallMs,
            boolean hasSamples, long peakHeapBytes, long heapDeltaBytes,
            double minTps, int budgetExceeded, long gcCount, long gcTimeMs) {
        final String base = jobLine(engine, jobId, blocks, wallMs);
        if (!hasSamples) {
            return base;
        }
        return base + String.format(
                "  heap-peak=%s heap-delta=%s minTPS=%.1f budget-exceeded=%d gc=%d/+%dms",
                formatMb(peakHeapBytes), formatMbDelta(heapDeltaBytes), minTps,
                budgetExceeded, gcCount, gcTimeMs);
    }

    /**
     * The buffered engine completion line.
     */
    public static String bufferedJobLine(int jobId, long blocks, long wallMs) {
        return jobLine("buffered", jobId, blocks, wallMs);
    }

    /**
     * The buffered engine completion line with memory + smoothness telemetry.
     */
    public static String bufferedJobLine(int jobId, long blocks, long wallMs,
            boolean hasSamples, long peakHeapBytes, long heapDeltaBytes,
            double minTps, int budgetExceeded, long gcCount, long gcTimeMs) {
        return jobLine("buffered", jobId, blocks, wallMs, hasSamples,
                peakHeapBytes, heapDeltaBytes, minTps, budgetExceeded,
                gcCount, gcTimeMs);
    }

    /**
     * The classic engine completion line.
     */
    public static String classicJobLine(int jobId, long blocks, long wallMs) {
        return jobLine("classic", jobId, blocks, wallMs);
    }

    /**
     * The classic engine completion line with memory + smoothness telemetry.
     */
    public static String classicJobLine(int jobId, long blocks, long wallMs,
            boolean hasSamples, long peakHeapBytes, long heapDeltaBytes,
            double minTps, int budgetExceeded, long gcCount, long gcTimeMs) {
        return jobLine("classic", jobId, blocks, wallMs, hasSamples,
                peakHeapBytes, heapDeltaBytes, minTps, budgetExceeded,
                gcCount, gcTimeMs);
    }
}
