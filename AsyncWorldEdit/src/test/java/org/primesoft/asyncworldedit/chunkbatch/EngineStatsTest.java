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

import static org.junit.Assert.*;
import org.junit.Test;

/**
 * Value tests for the shared engine completion stats: the avg blocks/sec math
 * (including the wall==0 divide-by-zero guard) and that the buffered and
 * classic completion lines are identical apart from the engine label.
 *
 * @author KAMKEEL
 */
public class EngineStatsTest {

    @Test
    public void avgBlocksPerSecBasic() {
        //1000 blocks in 1000ms == 1000 blocks/sec
        assertEquals(1000.0, EngineStats.avgBlocksPerSec(1000, 1000), 0.0);
        //5000 blocks in 250ms == 20000 blocks/sec
        assertEquals(20000.0, EngineStats.avgBlocksPerSec(5000, 250), 0.0);
    }

    @Test
    public void avgBlocksPerSecZeroWallIsGuarded() {
        //wall==0 must not divide by zero
        assertEquals(0.0, EngineStats.avgBlocksPerSec(1234, 0), 0.0);
    }

    @Test
    public void avgBlocksPerSecNegativeWallIsGuarded() {
        assertEquals(0.0, EngineStats.avgBlocksPerSec(1234, -5), 0.0);
    }

    @Test
    public void avgBlocksPerSecZeroBlocks() {
        assertEquals(0.0, EngineStats.avgBlocksPerSec(0, 1000), 0.0);
    }

    @Test
    public void classicJobLineFormat() {
        assertEquals(
                "[ENGINE] job 7 done: classic blocks=5000 wall=250ms avg=20000 blocks/sec",
                EngineStats.classicJobLine(7, 5000, 250));
    }

    @Test
    public void bufferedJobLineFormat() {
        assertEquals(
                "[ENGINE] job 7 done: buffered lane=fast blocks=5000 wall=250ms avg=20000 blocks/sec",
                EngineStats.bufferedJobLine(7, "fast", 5000, 250));
        assertEquals(
                "[ENGINE] job 7 done: buffered lane=blocks blocks=5000 wall=250ms avg=20000 blocks/sec",
                EngineStats.bufferedJobLine(7, "blocks", 5000, 250));
        assertEquals(
                "[ENGINE] job 7 done: buffered lane=mixed blocks=5000 wall=250ms avg=20000 blocks/sec",
                EngineStats.bufferedJobLine(7, "mixed", 5000, 250));
    }

    @Test
    public void jobLinesDifferOnlyByEngineLabel() {
        String classic = EngineStats.classicJobLine(42, 128, 64);
        String buffered = EngineStats.bufferedJobLine(42, "blocks", 128, 64);
        assertEquals(classic.replace("classic", "ENGINE_LABEL"),
                buffered.replace("buffered lane=blocks", "ENGINE_LABEL"));
    }

    @Test
    public void jobLineFloorsWallToOneMs() {
        //wall==0 is floored to 1ms so the string is always well formed
        assertEquals(
                "[ENGINE] job 1 done: classic blocks=0 wall=1ms avg=0 blocks/sec",
                EngineStats.classicJobLine(1, 0, 0));
    }

    //One megabyte in bytes
    private static final long MB = 1024L * 1024L;

    @Test
    public void formatMbRoundsToWholeMegabytes() {
        assertEquals("0MB", EngineStats.formatMb(0));
        assertEquals("1MB", EngineStats.formatMb(MB));
        assertEquals("512MB", EngineStats.formatMb(512 * MB));
        //1.4MB rounds down, 1.5MB rounds up (half up)
        assertEquals("1MB", EngineStats.formatMb(MB + 419430)); //~1.4MB
        assertEquals("2MB", EngineStats.formatMb(MB + MB / 2)); //1.5MB
    }

    @Test
    public void formatMbDeltaIsAlwaysSigned() {
        assertEquals("+38MB", EngineStats.formatMbDelta(38 * MB));
        assertEquals("-5MB", EngineStats.formatMbDelta(-5 * MB));
        //zero renders as a positive zero delta
        assertEquals("+0MB", EngineStats.formatMbDelta(0));
    }

    @Test
    public void tpsToMilliEncodesThousandths() {
        assertEquals(19400L, EngineStats.tpsToMilli(19.4));
        assertEquals(20000L, EngineStats.tpsToMilli(20.0));
        assertEquals(0L, EngineStats.tpsToMilli(0.0));
    }

    @Test
    public void minTpsMilliFoldsTheSmaller() {
        //a lower reading lowers the running minimum
        assertEquals(19400L, EngineStats.minTpsMilli(20000L, 19.4));
        //a higher reading leaves the running minimum untouched
        assertEquals(19400L, EngineStats.minTpsMilli(19400L, 20.0));
        //seeded from MAX_VALUE the first reading always wins
        assertEquals(18500L, EngineStats.minTpsMilli(Long.MAX_VALUE, 18.5));
    }

    @Test
    public void enrichedClassicJobLineFormat() {
        assertEquals(
                "[ENGINE] job 5 done: classic blocks=329156 wall=2307ms avg=142677 blocks/sec"
                + "  heap-peak=512MB heap-delta=+38MB minTPS=19.4 budget-exceeded=3 gc=17/+412ms",
                EngineStats.classicJobLine(5, 329156, 2307, true,
                        512 * MB, 38 * MB, 19.4, 3, 17, 412));
    }

    @Test
    public void enrichedBufferedJobLineFormat() {
        assertEquals(
                "[ENGINE] job 5 done: buffered lane=fast blocks=329156 wall=2307ms avg=142677 blocks/sec"
                + "  heap-peak=512MB heap-delta=+38MB minTPS=19.4 budget-exceeded=3 gc=17/+412ms",
                EngineStats.bufferedJobLine(5, "fast", 329156, 2307, true,
                        512 * MB, 38 * MB, 19.4, 3, 17, 412));
    }

    @Test
    public void enrichedJobLineZeroGcDeltaPrintsExplicitZero() {
        //a job with no GC activity must show gc=0/+0ms, not drop the field -
        //"zero collections" IS the buffered engine's proof metric
        assertEquals(
                "[ENGINE] job 2 done: buffered lane=blocks blocks=100 wall=50ms avg=2000 blocks/sec"
                + "  heap-peak=64MB heap-delta=+0MB minTPS=20.0 budget-exceeded=0 gc=0/+0ms",
                EngineStats.bufferedJobLine(2, "blocks", 100, 50, true,
                        64 * MB, 0, 20.0, 0, 0, 0));
    }

    @Test
    public void enrichedJobLinesDifferOnlyByEngineLabel() {
        String classic = EngineStats.classicJobLine(9, 128, 64, true,
                64 * MB, -5 * MB, 18.2, 1, 4, 33);
        String buffered = EngineStats.bufferedJobLine(9, "mixed", 128, 64, true,
                64 * MB, -5 * MB, 18.2, 1, 4, 33);
        assertEquals(classic.replace("classic", "ENGINE_LABEL"),
                buffered.replace("buffered lane=mixed", "ENGINE_LABEL"));
    }

    @Test
    public void enrichedJobLineWithoutSamplesIsBaseLineOnly() {
        //hasSamples==false must drop the telemetry fields entirely so the line
        //never prints misleading zeros
        String base = EngineStats.classicJobLine(7, 5000, 250);
        assertEquals(base,
                EngineStats.classicJobLine(7, 5000, 250, false,
                        999 * MB, 999 * MB, 5.0, 9, 42, 4200));
    }
}
