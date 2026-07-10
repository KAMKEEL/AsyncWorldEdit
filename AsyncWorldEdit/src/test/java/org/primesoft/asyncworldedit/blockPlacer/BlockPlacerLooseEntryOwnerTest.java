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

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import org.junit.Test;
import org.primesoft.asyncworldedit.api.blockPlacer.IBlockPlacer;
import org.primesoft.asyncworldedit.api.blockPlacer.IJobEntryListener;
import org.primesoft.asyncworldedit.api.blockPlacer.entries.IJobEntry;
import org.primesoft.asyncworldedit.api.blockPlacer.entries.JobStatus;
import org.primesoft.asyncworldedit.api.playerManager.IPlayerEntry;
import org.primesoft.asyncworldedit.blockPlacer.entries.IUndoRedoJob;
import org.primesoft.asyncworldedit.blockPlacer.entries.RedoJob;
import org.primesoft.asyncworldedit.blockPlacer.entries.UndoJob;

/**
 * Tests for {@link BlockPlacer#resolveLooseEntryOwner}: the fallback that
 * attributes classic queue entries carrying the loose job id -1 (undo/redo
 * replay writes - the changeset's blocks are unwrapped) to the player's
 * oldest live undo/redo job. Pure selection logic, driven with fake jobs.
 */
public class BlockPlacerLooseEntryOwnerTest {

    @Test
    public void noJobsResolvesToNull() {
        assertNull(BlockPlacer.resolveLooseEntryOwner(new IJobEntry[0]));
    }

    @Test
    public void plainJobsAreNeverReturned() {
        //A stray loose write of a normal edit must stay unattributed: only
        //undo/redo jobs replay unwrapped changeset blocks by design
        IJobEntry[] jobs = { new FakeJob(0), new FakeJob(1) };

        assertNull(BlockPlacer.resolveLooseEntryOwner(jobs));
    }

    @Test
    public void singleUndoRedoJobIsReturned() {
        FakeUndoRedoJob undo = new FakeUndoRedoJob(3);
        IJobEntry[] jobs = { new FakeJob(0), undo, new FakeJob(7) };

        assertSame(undo, BlockPlacer.resolveLooseEntryOwner(jobs));
    }

    @Test
    public void oldestOfSeveralUndoRedoJobsWins() {
        //Ids are handed out increasing among concurrently live jobs and the
        //queue drains FIFO, so the smallest id owns the draining entries -
        //regardless of the iteration order of the jobs array
        FakeUndoRedoJob oldest = new FakeUndoRedoJob(3);
        IJobEntry[] jobs = {
            new FakeUndoRedoJob(7), oldest, new FakeUndoRedoJob(5)
        };

        assertSame(oldest, BlockPlacer.resolveLooseEntryOwner(jobs));
    }

    @Test
    public void plainJobWithSmallerIdDoesNotShadowUndoRedoJob() {
        FakeUndoRedoJob undo = new FakeUndoRedoJob(4);
        IJobEntry[] jobs = { new FakeJob(0), undo };

        assertSame(undo, BlockPlacer.resolveLooseEntryOwner(jobs));
    }

    @Test
    public void undoAndRedoJobsCarryTheMarker() {
        //The production classes must stay attributable (compile-time only
        //guard: the jobs cannot be instantiated without the platform)
        assertTrue(IUndoRedoJob.class.isAssignableFrom(UndoJob.class));
        assertTrue(IUndoRedoJob.class.isAssignableFrom(RedoJob.class));
    }

    /**
     * Minimal fake job: only the job id matters to the resolver.
     */
    private static class FakeJob implements IJobEntry {

        private final int m_jobId;

        FakeJob(int jobId) {
            m_jobId = jobId;
        }

        @Override
        public int getJobId() {
            return m_jobId;
        }

        @Override
        public boolean isDemanding() {
            return false;
        }

        @Override
        public boolean process(IBlockPlacer bp) {
            return true;
        }

        @Override
        public void addStateChangedListener(IJobEntryListener listener) {
        }

        @Override
        public void removeStateChangedListener(IJobEntryListener listener) {
        }

        @Override
        public void cancel() {
        }

        @Override
        public String getName() {
            return "fake";
        }

        @Override
        public IPlayerEntry getPlayer() {
            return null;
        }

        @Override
        public JobStatus getStatus() {
            return JobStatus.PlacingBlocks;
        }

        @Override
        public String getStatusString() {
            return "";
        }

        @Override
        public boolean isTaskDone() {
            return false;
        }

        @Override
        public void setStatus(JobStatus newStatus) {
        }

        @Override
        public void taskDone() {
        }
    }

    /**
     * Fake history replay job: a fake job carrying the undo/redo marker.
     */
    private static final class FakeUndoRedoJob extends FakeJob
            implements IUndoRedoJob {

        FakeUndoRedoJob(int jobId) {
            super(jobId);
        }
    }
}
