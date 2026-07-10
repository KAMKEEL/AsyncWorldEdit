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
package org.primesoft.asyncworldedit.chunkbatch.undo;

import java.util.UUID;
import static org.junit.Assert.*;
import org.junit.Test;

/**
 * Lifecycle tests for the (player, job) capture sink registry: first
 * registration wins, loose job ids are rejected, per job unregistration
 * (which seals the sink - job ids are reused), the owner-bound resolution
 * that evicts stale entries of dead jobs, and the sink-wide safety net
 * sweep.
 *
 * @author KAMKEEL
 */
public class ColumnarUndoRegistryTest {

    /**
     * Recording sink: only identity and the jobDone (seal) signal matter
     */
    private static final class FakeSink implements ICaptureSink {

        int jobDoneCalls;

        @Override
        public void beginSection(int cx, int cz, int section, int firstSeq, int lastSeq) {
        }

        @Override
        public void capture(int slotIndex, int oldId, int oldData, int newId, int newData) {
        }

        @Override
        public void endSection() {
        }

        @Override
        public void captureCleared(int x, int y, int z, int oldId, int oldData,
                int newId, int newData, int seq) {
        }

        @Override
        public void jobDone() {
            jobDoneCalls++;
        }
    }

    private final Object m_ownerA = new Object();
    private final Object m_ownerB = new Object();

    @Test
    public void firstRegistrationWinsPerJob() {
        final UUID player = UUID.randomUUID();
        final FakeSink first = new FakeSink();
        final FakeSink second = new FakeSink();
        try {
            assertTrue(ColumnarUndoRegistry.register(player, 1, first, m_ownerA));
            assertFalse("a job has exactly one capture sink",
                    ColumnarUndoRegistry.register(player, 1, second, m_ownerA));
            assertSame(first, ColumnarUndoRegistry.get(player, 1));
        } finally {
            ColumnarUndoRegistry.unregister(player, 1);
        }
        assertNull(ColumnarUndoRegistry.get(player, 1));
    }

    @Test
    public void looseJobIdsAreNeverRegistered() {
        final UUID player = UUID.randomUUID();
        assertFalse("job id -1 (undo replays, loose writes) must never capture",
                ColumnarUndoRegistry.register(player, -1, new FakeSink(), m_ownerA));
        assertNull(ColumnarUndoRegistry.get(player, -1));
        assertNull(ColumnarUndoRegistry.resolveOwned(player, -1, m_ownerA));
    }

    @Test
    public void jobsOfDifferentPlayersAreIndependent() {
        final UUID alice = UUID.randomUUID();
        final UUID bob = UUID.randomUUID();
        final FakeSink aliceSink = new FakeSink();
        final FakeSink bobSink = new FakeSink();
        try {
            assertTrue(ColumnarUndoRegistry.register(alice, 3, aliceSink, m_ownerA));
            assertTrue(ColumnarUndoRegistry.register(bob, 3, bobSink, m_ownerB));
            assertSame(aliceSink, ColumnarUndoRegistry.get(alice, 3));
            assertSame(bobSink, ColumnarUndoRegistry.get(bob, 3));

            ColumnarUndoRegistry.unregister(alice, 3);
            assertNull(ColumnarUndoRegistry.get(alice, 3));
            assertSame(bobSink, ColumnarUndoRegistry.get(bob, 3));
        } finally {
            ColumnarUndoRegistry.unregister(alice, 3);
            ColumnarUndoRegistry.unregister(bob, 3);
        }
    }

    @Test
    public void unregisterSealsTheSink() {
        final UUID player = UUID.randomUUID();
        final FakeSink sink = new FakeSink();
        assertTrue(ColumnarUndoRegistry.register(player, 2, sink, m_ownerA));

        ColumnarUndoRegistry.unregister(player, 2);

        assertEquals("job ids are reused: an unregistered sink must be"
                + " sealed so a late capture cannot append to its history",
                1, sink.jobDoneCalls);
        //Idempotent: a second unregister finds nothing
        ColumnarUndoRegistry.unregister(player, 2);
        assertEquals(1, sink.jobDoneCalls);
    }

    @Test
    public void resolveOwnedReturnsOnlyTheOwnersSink() {
        final UUID player = UUID.randomUUID();
        final FakeSink sink = new FakeSink();
        try {
            assertTrue(ColumnarUndoRegistry.register(player, 4, sink, m_ownerA));
            assertSame(sink, ColumnarUndoRegistry.resolveOwned(player, 4, m_ownerA));
        } finally {
            ColumnarUndoRegistry.unregister(player, 4);
        }
    }

    @Test
    public void resolveOwnedEvictsAndSealsAForeignStaleEntry() {
        //Job id reuse: session A's job 5 died without unregistering (it
        //never created a buffer); session B's new job reuses id 5. B must
        //NOT bind A's sink - the stale entry is evicted and sealed so B
        //registers its own log.
        final UUID player = UUID.randomUUID();
        final FakeSink stale = new FakeSink();
        final FakeSink fresh = new FakeSink();
        try {
            assertTrue(ColumnarUndoRegistry.register(player, 5, stale, m_ownerA));

            assertNull("a foreign hit must not be trusted",
                    ColumnarUndoRegistry.resolveOwned(player, 5, m_ownerB));
            assertEquals("the stale sink must be sealed", 1, stale.jobDoneCalls);
            assertNull("the stale entry must be gone",
                    ColumnarUndoRegistry.get(player, 5));

            assertTrue("the new owner now registers its own sink",
                    ColumnarUndoRegistry.register(player, 5, fresh, m_ownerB));
            assertSame(fresh, ColumnarUndoRegistry.resolveOwned(player, 5, m_ownerB));
            assertEquals("the fresh sink stays unsealed", 0, fresh.jobDoneCalls);
        } finally {
            ColumnarUndoRegistry.unregister(player, 5);
        }
    }

    @Test
    public void unregisterSinkSweepsEveryJobOfTheSinkWithoutSealing() {
        final UUID player = UUID.randomUUID();
        final FakeSink shared = new FakeSink();
        final FakeSink other = new FakeSink();
        try {
            assertTrue(ColumnarUndoRegistry.register(player, 10, shared, m_ownerA));
            assertTrue(ColumnarUndoRegistry.register(player, 11, shared, m_ownerA));
            assertTrue(ColumnarUndoRegistry.register(player, 12, other, m_ownerB));

            ColumnarUndoRegistry.unregisterSink(shared);
            assertNull(ColumnarUndoRegistry.get(player, 10));
            assertNull(ColumnarUndoRegistry.get(player, 11));
            assertSame("the sweep must only touch the given sink",
                    other, ColumnarUndoRegistry.get(player, 12));
            assertEquals("the session close path closes the sink itself",
                    0, shared.jobDoneCalls);
        } finally {
            ColumnarUndoRegistry.unregister(player, 10);
            ColumnarUndoRegistry.unregister(player, 11);
            ColumnarUndoRegistry.unregister(player, 12);
        }
    }
}
