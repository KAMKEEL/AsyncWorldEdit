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
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.List;
import static org.junit.Assert.*;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.primesoft.asyncworldedit.api.IWorld;
import org.primesoft.asyncworldedit.api.blockPlacer.entries.IJobEntry;
import org.primesoft.asyncworldedit.api.blockPlacer.entries.JobStatus;
import org.primesoft.asyncworldedit.api.playerManager.IPlayerEntry;
import org.primesoft.asyncworldedit.chunkbatch.undo.ColumnarUndoRegistry;
import org.primesoft.asyncworldedit.chunkbatch.undo.ICaptureSink;

/**
 * Pure logic tests for the buffer-first block placement engine coordinator:
 * job keying + prune, concurrent producer/drain safety, round robin
 * fairness, the classic/buffered clear interplay, the shared section budget
 * overflow to classic and the progress counters.
 *
 * Every flush goes through an injected recording sink, so nothing touches
 * NMS or Bukkit.
 *
 * @author KAMKEEL
 */
public class JobBufferRegistryTest {

    /**
     * Recording flush sink: captures the final value of every flushed
     * position (forEachLastWriteOrder yields the last write per slot)
     */
    private static final class RecordingSink implements JobBufferRegistry.IFlushSink {

        final List<String> order = new CopyOnWriteArrayList<String>();
        final Map<String, int[]> latest = new ConcurrentHashMap<String, int[]>();
        final List<Integer> flushedChunkSizes = new CopyOnWriteArrayList<Integer>();
        final List<ICaptureSink> captureSinks = new CopyOnWriteArrayList<ICaptureSink>();

        @Override
        public void flush(World weWorld, IWorld aweWorld, final PendingChunk chunk,
                ICaptureSink captureSink) {
            if (captureSink != null) {
                captureSinks.add(captureSink);
            }
            flushedChunkSizes.add(chunk.getCount());
            chunk.forEachLastWriteOrder(new PendingChunk.IPendingBlockVisitor() {
                @Override
                public void visit(int x, int y, int z, int id, int data, boolean notify, int seq) {
                    String key = x + "," + y + "," + z;
                    order.add(key + ":" + id + ":" + data);
                    latest.put(key, new int[]{id, data});
                }
            });
        }
    }

    private static World weWorld() {
        return (World) Proxy.newProxyInstance(
                JobBufferRegistryTest.class.getClassLoader(),
                new Class<?>[]{World.class}, new InvocationHandler() {
            @Override
            public Object invoke(Object o, Method m, Object[] a) {
                if ("getName".equals(m.getName())) {
                    return "world";
                }
                return defaultValue(m);
            }
        });
    }

    private static IWorld aweWorld(final String name) {
        return (IWorld) Proxy.newProxyInstance(
                JobBufferRegistryTest.class.getClassLoader(),
                new Class<?>[]{IWorld.class}, new InvocationHandler() {
            @Override
            public Object invoke(Object o, Method m, Object[] a) {
                if ("getName".equals(m.getName())) {
                    return name;
                }
                return defaultValue(m);
            }
        });
    }

    private static IPlayerEntry player(final UUID uuid) {
        return (IPlayerEntry) Proxy.newProxyInstance(
                JobBufferRegistryTest.class.getClassLoader(),
                new Class<?>[]{IPlayerEntry.class}, new InvocationHandler() {
            @Override
            public Object invoke(Object o, Method m, Object[] a) {
                if ("getUUID".equals(m.getName())) {
                    return uuid;
                }
                if ("isPlayer".equals(m.getName())) {
                    return Boolean.TRUE;
                }
                return defaultValue(m);
            }
        });
    }

    /**
     * Job whose "task done" flag is flipped by the test to signal the
     * producer finished
     */
    private static final class FakeJob {

        final boolean[] done = new boolean[]{false};

        final IJobEntry proxy = (IJobEntry) Proxy.newProxyInstance(
                JobBufferRegistryTest.class.getClassLoader(),
                new Class<?>[]{IJobEntry.class}, new InvocationHandler() {
            @Override
            public Object invoke(Object o, Method m, Object[] a) {
                if ("isTaskDone".equals(m.getName())) {
                    return done[0];
                }
                if ("getStatus".equals(m.getName())) {
                    return done[0] ? JobStatus.Done : JobStatus.PlacingBlocks;
                }
                return defaultValue(m);
            }
        });

        void finish() {
            done[0] = true;
        }
    }

    private static Object defaultValue(Method m) {
        Class<?> rt = m.getReturnType();
        if (rt == boolean.class) {
            return Boolean.FALSE;
        }
        if (rt == int.class) {
            return 0;
        }
        if (rt == long.class) {
            return 0L;
        }
        return null;
    }

    private static final ChunkBatchWriter.IFlushLimit NO_LIMIT
            = new ChunkBatchWriter.IFlushLimit() {
                @Override
                public boolean shouldContinue() {
                    return true;
                }
            };

    @Before
    @After
    public void resetBudget() {
        //The section budget is a shared singleton; reset it around every test
        SectionBudget.getShared().release(Integer.MAX_VALUE);
        SectionBudget.getShared().setMaxSections(SectionBudget.DEFAULT_MAX_SECTIONS);
    }

    private static boolean buffer(JobBufferRegistry reg, IPlayerEntry p, int jobId,
            IJobEntry job, IWorld world, int x, int y, int z, int id, int data) {
        return reg.buffer(p, jobId, weWorld(), world, job, x, y, z, id, data, true);
    }

    @Test
    public void keyingSeparatesJobsAndPruneRemovesFinishedBuffers() {
        JobBufferRegistry reg = new JobBufferRegistry();
        IWorld world = aweWorld("world");
        IPlayerEntry p1 = player(new UUID(1, 1));
        IPlayerEntry p2 = player(new UUID(2, 2));
        FakeJob j1 = new FakeJob();
        FakeJob j2 = new FakeJob();
        FakeJob j1b = new FakeJob();

        //Same player, two job ids => two buffers; plus a second player
        buffer(reg, p1, 1, j1.proxy, world, 0, 64, 0, 1, 0);
        buffer(reg, p1, 2, j1b.proxy, world, 0, 64, 0, 2, 0);
        buffer(reg, p2, 1, j2.proxy, world, 0, 64, 0, 3, 0);
        assertEquals(3, reg.getBufferCount());

        //Only job 1 of player 1 is done: draining prunes just that buffer
        j1.finish();
        RecordingSink sink = new RecordingSink();
        reg.drainRoundRobin(NO_LIMIT, sink, false);

        assertEquals(1, sink.order.size());
        assertEquals("0,64,0:1:0", sink.order.get(0));
        assertEquals(2, reg.getBufferCount());

        //Force drains and prunes the rest
        j1b.finish();
        j2.finish();
        reg.drainRoundRobin(NO_LIMIT, sink, false);
        assertEquals(0, reg.getBufferCount());
    }

    /**
     * No-op capture sink; only its identity matters to the routing
     */
    private static final class FakeCaptureSink implements ICaptureSink {

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
    }

    @Test
    public void registeredCaptureSinkRidesEveryFlushAndUnregistersOnPrune() {
        JobBufferRegistry reg = new JobBufferRegistry();
        IWorld world = aweWorld("world");
        UUID uuid = new UUID(9, 9);
        IPlayerEntry p = player(uuid);
        FakeJob job = new FakeJob();

        final FakeCaptureSink capture = new FakeCaptureSink();
        try {
            assertTrue(ColumnarUndoRegistry.register(uuid, 5, capture));

            //Two chunks of the same job
            buffer(reg, p, 5, job.proxy, world, 0, 64, 0, 1, 0);
            buffer(reg, p, 5, job.proxy, world, 16, 64, 0, 2, 0);
            job.finish();

            RecordingSink sink = new RecordingSink();
            reg.drainRoundRobin(NO_LIMIT, sink, false);

            //Both chunk flushes carried the job's capture sink so the old
            //values are captured before each write
            assertEquals(2, sink.captureSinks.size());
            assertSame(capture, sink.captureSinks.get(0));
            assertSame(capture, sink.captureSinks.get(1));

            //The drained job was pruned and its registration dropped
            assertEquals(0, reg.getBufferCount());
            assertNull(ColumnarUndoRegistry.get(uuid, 5));
        } finally {
            ColumnarUndoRegistry.unregister(uuid, 5);
        }
    }

    @Test
    public void unregisteredJobsFlushWithoutACaptureSink() {
        JobBufferRegistry reg = new JobBufferRegistry();
        IWorld world = aweWorld("world");
        IPlayerEntry p = player(new UUID(10, 10));
        FakeJob job = new FakeJob();

        //Changeset mode / undo off / loose writes: nothing registered
        buffer(reg, p, 6, job.proxy, world, 0, 64, 0, 1, 0);
        job.finish();

        RecordingSink sink = new RecordingSink();
        reg.drainRoundRobin(NO_LIMIT, sink, false);

        assertEquals(1, sink.flushedChunkSizes.size());
        assertTrue("no registration = no capture, zero cost",
                sink.captureSinks.isEmpty());
    }

    @Test
    public void cancelDiscardUnregistersTheCaptureSink() {
        JobBufferRegistry reg = new JobBufferRegistry();
        IWorld world = aweWorld("world");
        UUID uuid = new UUID(11, 11);
        IPlayerEntry p = player(uuid);

        //A job that reports canceled: its buffers are discarded, never
        //placed, and the capture registration must not leak
        final boolean[] canceled = new boolean[]{false};
        IJobEntry job = (IJobEntry) Proxy.newProxyInstance(
                JobBufferRegistryTest.class.getClassLoader(),
                new Class<?>[]{IJobEntry.class}, new InvocationHandler() {
            @Override
            public Object invoke(Object o, Method m, Object[] a) {
                if ("getStatus".equals(m.getName())) {
                    return canceled[0] ? JobStatus.Canceled : JobStatus.PlacingBlocks;
                }
                return defaultValue(m);
            }
        });

        final FakeCaptureSink capture = new FakeCaptureSink();
        try {
            assertTrue(ColumnarUndoRegistry.register(uuid, 7, capture));
            buffer(reg, p, 7, job, world, 0, 64, 0, 1, 0);

            canceled[0] = true;
            RecordingSink sink = new RecordingSink();
            reg.drainRoundRobin(NO_LIMIT, sink, false);

            assertTrue("canceled buffers are dropped, not flushed",
                    sink.flushedChunkSizes.isEmpty());
            assertEquals(0, reg.getBufferCount());
            assertNull(ColumnarUndoRegistry.get(uuid, 7));
        } finally {
            ColumnarUndoRegistry.unregister(uuid, 7);
        }
    }

    @Test
    public void producerDrainSmokeNoLostWritesAndMonotonicSequence() throws Exception {
        final JobBufferRegistry reg = new JobBufferRegistry();
        final IWorld world = aweWorld("world");
        final IPlayerEntry p = player(new UUID(7, 7));
        final FakeJob job = new FakeJob();

        final int count = 4000;
        final CountDownLatch producerDone = new CountDownLatch(1);
        final RecordingSink sink = new RecordingSink();

        //Producer thread buffers 'count' distinct positions spread over many
        //chunks; the main thread races force-drains while it produces
        Thread producer = new Thread(new Runnable() {
            @Override
            public void run() {
                for (int i = 0; i < count; i++) {
                    int x = (i % 64);
                    int z = (i / 64);
                    reg.buffer(p, 1, weWorld(), world, job.proxy,
                            x, 64, z, 1 + (i % 10), i % 16, true);
                }
                job.finish();
                producerDone.countDown();
            }
        });
        producer.start();

        //Interleave force-drains with production (exercises the detach vs.
        //produce race). Force so partial chunks flush mid production.
        while (producerDone.getCount() > 0) {
            reg.drainRoundRobin(NO_LIMIT, sink, true);
        }
        producer.join();
        //Final drain: everything the racing drains left behind
        reg.drainRoundRobin(NO_LIMIT, sink, true);

        //No lost writes: every distinct position was flushed exactly once
        assertEquals(count, sink.order.size());
        Set<String> keys = new HashSet<String>(sink.latest.keySet());
        assertEquals(count, keys.size());

        //Monotonic global sequence: exactly one increment per buffered write
        assertEquals(count, reg.getWriteSeq());

        //Nothing left buffered, buffer pruned
        assertFalse(reg.hasWork());
        assertEquals(0, reg.getBufferCount());
    }

    @Test
    public void lastWriteWinsPerSlot() {
        JobBufferRegistry reg = new JobBufferRegistry();
        IWorld world = aweWorld("world");
        IPlayerEntry p = player(new UUID(3, 3));
        FakeJob job = new FakeJob();

        //Three writes to the same position, last one wins
        buffer(reg, p, 1, job.proxy, world, 5, 64, 5, 1, 0);
        buffer(reg, p, 1, job.proxy, world, 5, 64, 5, 20, 2);
        buffer(reg, p, 1, job.proxy, world, 5, 64, 5, 35, 7);
        job.finish();

        RecordingSink sink = new RecordingSink();
        reg.drainRoundRobin(NO_LIMIT, sink, false);

        assertEquals(1, sink.order.size());
        assertEquals("5,64,5:35:7", sink.order.get(0));
    }

    @Test
    public void roundRobinFairnessAcrossThreeJobsBudgetLimited() {
        final JobBufferRegistry reg = new JobBufferRegistry();
        IWorld world = aweWorld("world");
        FakeJob[] jobs = {new FakeJob(), new FakeJob(), new FakeJob()};
        //Each job in a different chunk column so it has one chunk per job
        for (int j = 0; j < 3; j++) {
            IPlayerEntry p = player(new UUID(100, j));
            //two chunks per job (two separate chunk columns)
            buffer(reg, p, 1, jobs[j].proxy, world, j * 16, 64, 0, 1, 0);
            buffer(reg, p, 1, jobs[j].proxy, world, j * 16 + 128, 64, 0, 2, 0);
            jobs[j].finish();
        }

        //Budget lets exactly one chunk flush per drain call (the first chunk
        //always flushes so carry over never starves, then the limit stops the
        //pass): assert the jobs are serviced in an interleaved (round robin)
        //fashion, not one job fully drained before the next
        ChunkBatchWriter.IFlushLimit oneChunk = new ChunkBatchWriter.IFlushLimit() {
            @Override
            public boolean shouldContinue() {
                return false;
            }
        };

        RecordingSink sink = new RecordingSink();
        //6 chunks total, one per drain: after 3 drains each job flushed once
        for (int i = 0; i < 3; i++) {
            reg.drainRoundRobin(oneChunk, sink, false);
        }
        //Three chunks flushed, one from each job (fairness)
        assertEquals(3, sink.order.size());
        Set<Integer> servicedColumns = new HashSet<Integer>();
        for (String s : sink.order) {
            servicedColumns.add(Integer.parseInt(s.substring(0, s.indexOf(','))) / 16);
        }
        //Columns 0,1,2 => each of the three jobs got a chunk in the first pass
        assertTrue(servicedColumns.contains(0));
        assertTrue(servicedColumns.contains(1));
        assertTrue(servicedColumns.contains(2));

        //Drain the rest: all six chunks eventually flush
        reg.drainRoundRobin(NO_LIMIT, sink, false);
        assertEquals(6, sink.order.size());
        assertFalse(reg.hasWork());
    }

    @Test
    public void clearBufferedDropsSlotAndOverlayMiss() {
        JobBufferRegistry reg = new JobBufferRegistry();
        IWorld world = aweWorld("world");
        IPlayerEntry p = player(new UUID(4, 4));
        FakeJob job = new FakeJob();

        buffer(reg, p, 1, job.proxy, world, 8, 70, 8, 42, 3);
        //Overlay (producer read-your-writes) sees the buffered block
        int slot = reg.overlayGet(world, 8, 70, 8);
        assertTrue(slot != SectionMath.EMPTY_SLOT);
        assertEquals(42, SectionMath.slotId(slot));

        //A classic write at the position drops the buffered value
        reg.clearBuffered(world, 8, 70, 8);
        assertEquals(SectionMath.EMPTY_SLOT, reg.overlayGet(world, 8, 70, 8));

        //And the flush no longer places it
        job.finish();
        RecordingSink sink = new RecordingSink();
        reg.drainRoundRobin(NO_LIMIT, sink, false);
        assertTrue(sink.order.isEmpty());
    }

    @Test
    public void classicVsBufferedSamePositionBothDirections() {
        JobBufferRegistry reg = new JobBufferRegistry();
        IWorld world = aweWorld("world");
        IPlayerEntry p = player(new UUID(5, 5));

        //Direction 1: buffered first, then a LATER classic write at P (e.g.
        //budget filled). The classic write clears the buffered value so the
        //newer classic block is not overwritten at flush.
        FakeJob jobA = new FakeJob();
        buffer(reg, p, 1, jobA.proxy, world, 1, 64, 1, 1, 0);
        reg.clearBuffered(world, 1, 64, 1);       //classic write at P
        jobA.finish();
        RecordingSink sinkA = new RecordingSink();
        reg.drainRoundRobin(NO_LIMIT, sinkA, false);
        assertTrue("buffered P must not be flushed over the classic write",
                sinkA.order.isEmpty());

        //Direction 2 (across carry-over): classic write at P first (nothing
        //buffered to clear), then a LATER buffered write at P wins - it
        //flushes after the classic write.
        FakeJob jobB = new FakeJob();
        reg.clearBuffered(world, 2, 64, 2);       //classic write at P, no buffered yet
        buffer(reg, p, 2, jobB.proxy, world, 2, 64, 2, 9, 1);
        jobB.finish();
        RecordingSink sinkB = new RecordingSink();
        reg.drainRoundRobin(NO_LIMIT, sinkB, false);
        assertEquals(1, sinkB.order.size());
        assertEquals("2,64,2:9:1", sinkB.order.get(0));
    }

    @Test
    public void sharedSectionBudgetOverflowsToClassic() {
        JobBufferRegistry reg = new JobBufferRegistry();
        IWorld world = aweWorld("world");
        IPlayerEntry p = player(new UUID(6, 6));
        FakeJob job = new FakeJob();

        //Cap the shared budget at two section buffers
        SectionBudget.getShared().setMaxSections(2);

        //Two distinct sections fit (y 0 and y 16 are different sections)
        assertTrue(buffer(reg, p, 1, job.proxy, world, 0, 0, 0, 1, 0));
        assertTrue(buffer(reg, p, 1, job.proxy, world, 0, 16, 0, 1, 0));
        assertEquals(2, SectionBudget.getShared().getUsed());

        //A third NEW section is refused => caller must use the classic path
        assertFalse(buffer(reg, p, 1, job.proxy, world, 0, 32, 0, 1, 0));
        //But writes into an already allocated section stay free (buffered)
        assertTrue(buffer(reg, p, 1, job.proxy, world, 1, 1, 1, 1, 0));
        assertEquals(2, SectionBudget.getShared().getUsed());

        //Flushing releases the budget back to the shared pool
        job.finish();
        reg.drainRoundRobin(NO_LIMIT, new RecordingSink(), false);
        assertEquals(0, SectionBudget.getShared().getUsed());
    }

    @Test
    public void sectionBudgetSharedWithChunkBatchWindow() {
        //The window (ChunkBatchWriter path) and the job buffers draw from the
        //same shared pool: reservations held by one reduce what the other can
        //acquire.
        JobBufferRegistry reg = new JobBufferRegistry();
        IWorld world = aweWorld("world");
        IPlayerEntry p = player(new UUID(8, 8));
        FakeJob job = new FakeJob();

        SectionBudget.getShared().setMaxSections(3);
        //Simulate the window already holding two section reservations
        assertTrue(SectionBudget.getShared().tryAcquire());
        assertTrue(SectionBudget.getShared().tryAcquire());

        //Only one section left for the job buffers
        assertTrue(buffer(reg, p, 1, job.proxy, world, 0, 0, 0, 1, 0));
        assertFalse(buffer(reg, p, 1, job.proxy, world, 0, 16, 0, 1, 0));

        //Release the window's reservations, drain the job buffer
        SectionBudget.getShared().release(2);
        job.finish();
        reg.drainRoundRobin(NO_LIMIT, new RecordingSink(), false);
        assertEquals(0, SectionBudget.getShared().getUsed());
    }

    @Test
    public void progressCountersTrackBufferedAndFlushed() {
        JobBufferRegistry reg = new JobBufferRegistry();
        IWorld world = aweWorld("world");
        IPlayerEntry p = player(new UUID(9, 9));
        FakeJob job = new FakeJob();

        buffer(reg, p, 1, job.proxy, world, 0, 64, 0, 1, 0);
        buffer(reg, p, 1, job.proxy, world, 1, 64, 0, 1, 0);
        buffer(reg, p, 1, job.proxy, world, 2, 64, 0, 1, 0);
        //Same position rewrite does not add a new queued block
        buffer(reg, p, 1, job.proxy, world, 2, 64, 0, 5, 0);

        //Three distinct positions are queued/buffered for the player
        assertEquals(3, reg.getBufferedCount(p));

        job.finish();
        reg.drainRoundRobin(NO_LIMIT, new RecordingSink(), false);

        //After the flush nothing is queued and the flushed debug counter is
        //truthful
        assertEquals(0, reg.getBufferedCount(p));
        assertEquals(3, reg.getLastFlushedBlocks());
        assertEquals(1, reg.getLastFlushedChunks());
    }

    @Test
    public void partialChunksOnlyFlushWhenJobDoneOrForced() {
        JobBufferRegistry reg = new JobBufferRegistry();
        IWorld world = aweWorld("world");
        IPlayerEntry p = player(new UUID(11, 11));
        FakeJob job = new FakeJob();

        buffer(reg, p, 1, job.proxy, world, 0, 64, 0, 1, 0);

        //Job still producing: a normal (non force) drain flushes nothing
        RecordingSink sink = new RecordingSink();
        reg.drainRoundRobin(NO_LIMIT, sink, false);
        assertTrue(sink.order.isEmpty());
        assertTrue(reg.hasWork());

        //Force (shutdown / reload / demanding entry) flushes the partial chunk
        reg.drainRoundRobin(NO_LIMIT, sink, true);
        assertEquals(1, sink.order.size());
        assertFalse(reg.hasWork());
    }
}
