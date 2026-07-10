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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/**
 * Tests for {@link AdaptiveTickBudget}. All timestamps are driven by a fake
 * clock (a plain counter advanced by the test), no wall clock is involved.
 *
 * The tick spacings are chosen so that all TPS values and budget computations
 * are exact in double arithmetic, which allows exact-value assertions.
 */
public class AdaptiveTickBudgetTest {

    private static final long MS = 1000000L;

    /**
     * Fake clock: current time in nanoseconds
     */
    private long m_now = 0;

    /**
     * Advance the fake clock and report a tick start
     */
    private long tick(AdaptiveTickBudget budget, long spacingNanos) {
        m_now += spacingNanos;
        return budget.onTickStart(m_now);
    }

    /**
     * Report ticks with a fixed spacing, return the last granted budget
     */
    private long ticks(AdaptiveTickBudget budget, long spacingNanos, int count) {
        long result = 0;
        for (int i = 0; i < count; i++) {
            result = tick(budget, spacingNanos);
        }
        return result;
    }

    @Test
    public void firstTickGrantsBaseBudget() {
        AdaptiveTickBudget budget = new AdaptiveTickBudget(18, 20, 30, 2, 1, 20);

        long granted = budget.onTickStart(0);

        //With no history the budget assumes a healthy server: base budget,
        //no idle extra
        assertEquals(20 * MS, granted);
        assertEquals(18.0, budget.getTpsEstimate(), 0.0);
    }

    @Test
    public void healthyTpsGrantsBaseBudget() {
        AdaptiveTickBudget budget = new AdaptiveTickBudget(16, 20, 30, 2, 1, 20);

        //5 ticks spaced 62.5 ms apart: 4 ticks over 0.25 s = exactly 16 TPS
        long granted = ticks(budget, 62500000L, 5);

        assertEquals(16.0, budget.getTpsEstimate(), 0.0);
        assertEquals(20 * MS, granted);
    }

    @Test
    public void idleServerGrantsBaseBudgetPlusFullExtra() {
        AdaptiveTickBudget budget = new AdaptiveTickBudget(16, 20, 30, 2, 1, 20);

        //6 ticks spaced 50 ms apart: 5 ticks over 0.25 s = exactly 20 TPS
        long granted = ticks(budget, 50000000L, 6);

        assertEquals(20.0, budget.getTpsEstimate(), 0.0);
        assertEquals(50 * MS, granted);
    }

    @Test
    public void idleExtraScalesWithHeadroom() {
        AdaptiveTickBudget budget = new AdaptiveTickBudget(10, 20, 30, 2, 1, 20);

        //5 ticks spaced 62.5 ms apart: 4 ticks over 0.25 s = exactly 16 TPS.
        //Idle fraction = (16 - 10) / (20 - 10) = 0.6,
        //budget = 20 ms + 0.6 * 30 ms = 38 ms
        long granted = ticks(budget, 62500000L, 5);

        assertEquals(16.0, budget.getTpsEstimate(), 0.0);
        assertEquals(38 * MS, granted);
    }

    @Test
    public void degradedTpsShrinksBudgetProportionally() {
        AdaptiveTickBudget budget = new AdaptiveTickBudget(16, 20, 30, 2, 1, 20);

        //5 ticks spaced 125 ms apart: 4 ticks over 0.5 s = exactly 8 TPS,
        //half of the 16 TPS target -> half of the base budget
        long granted = ticks(budget, 125000000L, 5);

        assertEquals(8.0, budget.getTpsEstimate(), 0.0);
        assertEquals(10 * MS, granted);
    }

    @Test
    public void budgetFloorIsRespected() {
        AdaptiveTickBudget budget = new AdaptiveTickBudget(16, 20, 30, 2, 1, 20);

        //5 ticks spaced 1 s apart: 4 ticks over 4 s = exactly 1 TPS.
        //Proportional budget would be 20 ms / 16 = 1.25 ms, the 2 ms floor wins
        long granted = ticks(budget, 1000000000L, 5);

        assertEquals(1.0, budget.getTpsEstimate(), 0.0);
        assertEquals(2 * MS, granted);
    }

    @Test
    public void tpsEstimateUsesRollingWindow() {
        //Window of 4 samples
        AdaptiveTickBudget budget = new AdaptiveTickBudget(18, 20, 30, 2, 1, 4);

        //Fill the window with 125 ms spacing: 3 ticks over 0.375 s = 8 TPS
        ticks(budget, 125000000L, 4);
        assertEquals(8.0, budget.getTpsEstimate(), 0.0);

        //Recover: 4 more ticks with 62.5 ms spacing push all slow samples out
        //of the window: 3 ticks over 0.1875 s = exactly 16 TPS
        ticks(budget, 62500000L, 4);
        assertEquals(16.0, budget.getTpsEstimate(), 0.0);
    }

    @Test
    public void tpsEstimateScalesWithTicksPerRun() {
        //The placer runs every 5 server ticks
        AdaptiveTickBudget budget = new AdaptiveTickBudget(18, 20, 30, 2, 5, 20);

        //4 runs spaced 250 ms apart: 3 * 5 ticks over 0.75 s = exactly 20 TPS
        ticks(budget, 250000000L, 4);

        assertEquals(20.0, budget.getTpsEstimate(), 0.0);
    }

    @Test
    public void tpsEstimateIsClampedDuringCatchUpAfterStall() {
        AdaptiveTickBudget budget = new AdaptiveTickBudget(16, 20, 30, 2, 1, 20);

        //A long stall, then the scheduler fires the missed runs back to
        //back: 19 catch-up runs spaced 25 ms apart with 1 tick per run
        //= raw 40 TPS over the window, twice the physical ceiling
        tick(budget, 5000000000L);
        long granted = ticks(budget, 25000000L, 19);

        //The estimate is clamped to the 20 TPS ceiling...
        assertEquals(20.0, budget.getTpsEstimate(), 0.0);
        //...and the budget is exactly the healthy full-speed budget (the
        //clamp is budget-neutral: the idle fraction is already capped at
        //1.0 for any estimate at or above the target)
        assertEquals(50 * MS, granted);
    }

    @Test
    public void shouldContinueStopsExactlyAtBudget() {
        AdaptiveTickBudget budget = new AdaptiveTickBudget(16, 20, 30, 2, 1, 20);

        //Exactly 16 TPS -> exactly the 20 ms base budget
        ticks(budget, 62500000L, 5);
        assertEquals(20 * MS, budget.getBudgetNanos());

        assertTrue(budget.shouldContinue(0));
        assertTrue(budget.shouldContinue(20 * MS - 1));
        assertFalse(budget.shouldContinue(20 * MS));
        assertFalse(budget.shouldContinue(20 * MS + 1));
    }
}
