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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import static org.junit.Assert.*;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.primesoft.asyncworldedit.api.IWorld;
import org.primesoft.asyncworldedit.api.blockPlacer.entries.IJobEntry;
import org.primesoft.asyncworldedit.api.blockPlacer.entries.JobStatus;
import org.primesoft.asyncworldedit.api.playerManager.IPlayerEntry;

/**
 * Exact-value tests for the region streamed flushing of the buffer-first
 * engine: window watermark trigger, least-recently-written selection order,
 * the staleness run clock (each drainRoundRobin call is one fake placer
 * run), rewrite-after-flush last-write-wins, cancel-drops-unflushed,
 * overlay misses after an early flush, forced full flush and the budget
 * carry-over of a streamed selection.
 *
 * Every flush goes through an injected recording sink; nothing touches NMS
 * or Bukkit.
 *
 * @author KAMKEEL
 */
public class JobBufferStreamingTest {

    /**
     * Recording flush sink (same shape as the registry test's sink): keeps
     * the flush order and the final value per position
     */
    private static final class RecordingSink implements JobBufferRegistry.IFlushSink {

        final List<String> order = new CopyOnWriteArrayList<String>();
        final Map<String, int[]> latest = new ConcurrentHashMap<String, int[]>();
        final List<Long> flushedChunkKeys = new CopyOnWriteArrayList<Long>();

        @Override
        public void flush(World weWorld, IWorld aweWorld, final PendingChunk chunk) {
            flushedChunkKeys.add(SectionMath.chunkKey(chunk.getX(), chunk.getZ()));
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
                JobBufferStreamingTest.class.getClassLoader(),
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
                JobBufferStreamingTest.class.getClassLoader(),
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
                JobBufferStreamingTest.class.getClassLoader(),
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
     * Job whose status is driven by the test: producing, done or canceled
     */
    private static final class FakeJob {

        final JobStatus[] status = new JobStatus[]{JobStatus.PlacingBlocks};

        final IJobEntry proxy = (IJobEntry) Proxy.newProxyInstance(
                JobBufferStreamingTest.class.getClassLoader(),
                new Class<?>[]{IJobEntry.class}, new InvocationHandler() {
            @Override
            public Object invoke(Object o, Method m, Object[] a) {
                if ("isTaskDone".equals(m.getName())) {
                    return status[0] == JobStatus.Done;
                }
                if ("getStatus".equals(m.getName())) {
                    return status[0];
                }
                return defaultValue(m);
            }
        });

        void finish() {
            status[0] = JobStatus.Done;
        }

        void cancel() {
            status[0] = JobStatus.Canceled;
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

    /**
     * Budget that refuses more time after the first (always granted) chunk
     * of a pass: exactly one chunk flushes per drain call
     */
    private static final ChunkBatchWriter.IFlushLimit ONE_CHUNK
            = new ChunkBatchWriter.IFlushLimit() {
                @Override
                public boolean shouldContinue() {
                    return false;
                }
            };

    @Before
    @After
    public void resetBudget() {
        SectionBudget.getShared().release(Integer.MAX_VALUE);
        SectionBudget.getShared().setMaxSections(SectionBudget.DEFAULT_MAX_SECTIONS);
    }

    private static boolean buffer(JobBufferRegistry reg, IPlayerEntry p, int jobId,
            IJobEntry job, IWorld world, int x, int y, int z, int id, int data) {
        return reg.buffer(p, jobId, weWorld(), world, job, x, y, z, id, data, true);
    }

    /**
     * A registry with streaming configured for the test (the staleness and
     * window knobs default to values that keep the other trigger inert)
     */
    private static JobBufferRegistry streamingRegistry(int windowSections, int staleRuns) {
        JobBufferRegistry reg = new JobBufferRegistry();
        reg.configureStream(true, windowSections, staleRuns);
        return reg;
    }

    @Test
    public void watermarkTriggersAtExactSectionCount() {
        //Window of 2 live sections; staleness inert
        JobBufferRegistry reg = streamingRegistry(2, 1000);
        IWorld world = aweWorld("world");
        IPlayerEntry p = player(new UUID(21, 1));
        FakeJob job = new FakeJob();

        //Two chunks, one section each: exactly AT the watermark - nothing
        //is ready
        buffer(reg, p, 1, job.proxy, world, 0, 64, 0, 1, 0);
        buffer(reg, p, 1, job.proxy, world, 16, 64, 0, 2, 0);
        RecordingSink sink = new RecordingSink();
        reg.drainRoundRobin(NO_LIMIT, sink, false);
        assertEquals(0, sink.order.size());
        assertEquals(0, reg.getLastStreamedChunks());
        assertEquals(0, reg.getLastWindowEvictions());

        //A third section pushes the job OVER the watermark: hysteresis
        //drains it to HALF the window (2/2 = 1 live section), evicting the
        //two least recently written chunks in write order
        buffer(reg, p, 1, job.proxy, world, 32, 64, 0, 3, 0);
        reg.drainRoundRobin(NO_LIMIT, sink, false);
        assertEquals(2, sink.order.size());
        assertEquals("0,64,0:1:0", sink.order.get(0));
        assertEquals("16,64,0:2:0", sink.order.get(1));
        assertEquals(2, reg.getLastStreamedChunks());
        assertEquals(2, reg.getLastWindowEvictions());
        //Down to the low watermark, not just below the window
        assertEquals(1, SectionBudget.getShared().getUsed());
        assertTrue(reg.hasWork());
    }

    @Test
    public void leastRecentlyWrittenChunksStreamFirst() {
        //Window of 2 with three live sections: hysteresis evicts down to
        //one live section, so exactly the two least-recently-written
        //chunks stream out, in ascending last-write order
        JobBufferRegistry reg = streamingRegistry(2, 1000);
        IWorld world = aweWorld("world");
        IPlayerEntry p = player(new UUID(21, 2));
        FakeJob job = new FakeJob();

        //Write order: A, B, C - then rewrite A, making it the most recent
        buffer(reg, p, 1, job.proxy, world, 0, 64, 0, 1, 0);    //A
        buffer(reg, p, 1, job.proxy, world, 16, 64, 0, 2, 0);   //B
        buffer(reg, p, 1, job.proxy, world, 32, 64, 0, 3, 0);   //C
        buffer(reg, p, 1, job.proxy, world, 1, 64, 0, 4, 0);    //A again

        RecordingSink sink = new RecordingSink();
        reg.drainRoundRobin(NO_LIMIT, sink, false);

        //B then C streamed (least recently written first); A - the most
        //recently written - is the one kept live
        assertEquals(2, sink.flushedChunkKeys.size());
        assertEquals(Long.valueOf(SectionMath.chunkKey(1, 0)), sink.flushedChunkKeys.get(0));
        assertEquals(Long.valueOf(SectionMath.chunkKey(2, 0)), sink.flushedChunkKeys.get(1));
        assertEquals(2, reg.getLastWindowEvictions());
        assertEquals(SectionMath.EMPTY_SLOT, reg.overlayGet(world, 16, 64, 0));
        assertTrue(reg.overlayGet(world, 0, 64, 0) != SectionMath.EMPTY_SLOT);
    }

    @Test
    public void stalenessFlushesAfterExactlyStaleRuns() {
        //Window inert; a chunk becomes ready after exactly 3 runs without a
        //write (each drain call is one fake placer run)
        JobBufferRegistry reg = streamingRegistry(1000, 3);
        IWorld world = aweWorld("world");
        IPlayerEntry p = player(new UUID(21, 3));
        FakeJob job = new FakeJob();

        buffer(reg, p, 1, job.proxy, world, 0, 64, 0, 7, 1);

        RecordingSink sink = new RecordingSink();
        reg.drainRoundRobin(NO_LIMIT, sink, false);   //run 1
        assertEquals(0, sink.order.size());
        reg.drainRoundRobin(NO_LIMIT, sink, false);   //run 2
        assertEquals(0, sink.order.size());
        reg.drainRoundRobin(NO_LIMIT, sink, false);   //run 3 - stale now
        assertEquals(1, sink.order.size());
        assertEquals("0,64,0:7:1", sink.order.get(0));
        assertEquals(1, reg.getLastStreamedChunks());
        //Staleness pick, not a window eviction
        assertEquals(0, reg.getLastWindowEvictions());
    }

    @Test
    public void writeResetsTheStalenessClockOfItsChunk() {
        JobBufferRegistry reg = streamingRegistry(1000, 3);
        IWorld world = aweWorld("world");
        IPlayerEntry p = player(new UUID(21, 4));
        FakeJob job = new FakeJob();

        buffer(reg, p, 1, job.proxy, world, 0, 64, 0, 7, 1);

        RecordingSink sink = new RecordingSink();
        reg.drainRoundRobin(NO_LIMIT, sink, false);   //run 1
        reg.drainRoundRobin(NO_LIMIT, sink, false);   //run 2
        //A write in run 2 restamps the chunk: two more runs stay quiet
        buffer(reg, p, 1, job.proxy, world, 1, 64, 0, 8, 2);
        reg.drainRoundRobin(NO_LIMIT, sink, false);   //run 3
        reg.drainRoundRobin(NO_LIMIT, sink, false);   //run 4
        assertEquals(0, sink.order.size());
        //Run 5 = 3 runs after the last write - now it streams
        reg.drainRoundRobin(NO_LIMIT, sink, false);
        assertEquals(2, sink.order.size());
    }

    @Test
    public void rewriteAfterStreamedFlushLastWriteWins() {
        //The chunk streams out mid job; a later producer write to the same
        //position lands in a fresh PendingChunk (detach-before-flush
        //contract) and flushes later - last write wins in the world
        JobBufferRegistry reg = streamingRegistry(1, 1000);
        IWorld world = aweWorld("world");
        IPlayerEntry p = player(new UUID(21, 5));
        FakeJob job = new FakeJob();

        //Two sections in one chunk put the job over the window of 1
        buffer(reg, p, 1, job.proxy, world, 0, 0, 0, 11, 1);
        buffer(reg, p, 1, job.proxy, world, 0, 16, 0, 12, 2);

        RecordingSink sink = new RecordingSink();
        reg.drainRoundRobin(NO_LIMIT, sink, false);
        assertEquals(2, sink.order.size());
        assertFalse(reg.hasWork());

        //Producer rewrites the streamed position: a fresh chunk buffer
        buffer(reg, p, 1, job.proxy, world, 0, 0, 0, 99, 9);
        assertTrue(reg.hasWork());

        job.finish();
        reg.drainRoundRobin(NO_LIMIT, sink, false);

        //The rewrite flushed after the streamed value: last write wins
        assertEquals(3, sink.order.size());
        assertEquals("0,0,0:99:9", sink.order.get(2));
        assertArrayEquals(new int[]{99, 9}, sink.latest.get("0,0,0"));
        assertEquals(0, reg.getBufferCount());
    }

    @Test
    public void cancelDropsUnflushedOnly() {
        JobBufferRegistry reg = streamingRegistry(1, 1000);
        IWorld world = aweWorld("world");
        IPlayerEntry p = player(new UUID(21, 6));
        FakeJob job = new FakeJob();

        //Chunk A streams out (window of 1, two sections in chunk A)
        buffer(reg, p, 1, job.proxy, world, 0, 0, 0, 5, 0);
        buffer(reg, p, 1, job.proxy, world, 0, 16, 0, 6, 0);
        RecordingSink sink = new RecordingSink();
        reg.drainRoundRobin(NO_LIMIT, sink, false);
        assertEquals(2, sink.order.size());

        //Chunk B is still buffered when the job is canceled
        buffer(reg, p, 1, job.proxy, world, 16, 64, 0, 7, 0);
        job.cancel();
        reg.drainRoundRobin(NO_LIMIT, sink, false);

        //The unflushed chunk was dropped, not placed; the streamed blocks
        //stay (they are undoable through the operation time change set)
        assertEquals(2, sink.order.size());
        assertNull(sink.latest.get("16,64,0"));
        assertEquals(0, reg.getBufferCount());
        assertFalse(reg.hasWork());
        assertEquals(0, reg.getBufferedCount(p));
        assertEquals(0, SectionBudget.getShared().getUsed());
    }

    @Test
    public void overlayMissesAfterStreamedFlush() {
        //Read-your-writes after an early flush: the overlay answers only
        //for live buffers; a flushed position falls through to the world
        //read path
        JobBufferRegistry reg = streamingRegistry(1, 1000);
        IWorld world = aweWorld("world");
        IPlayerEntry p = player(new UUID(21, 7));
        FakeJob job = new FakeJob();

        buffer(reg, p, 1, job.proxy, world, 0, 0, 0, 33, 3);
        buffer(reg, p, 1, job.proxy, world, 0, 16, 0, 34, 4);
        int slot = reg.overlayGet(world, 0, 0, 0);
        assertEquals(33, SectionMath.slotId(slot));

        reg.drainRoundRobin(NO_LIMIT, new RecordingSink(), false);

        //Flushed: the data is in the world now, the overlay must miss
        assertEquals(SectionMath.EMPTY_SLOT, reg.overlayGet(world, 0, 0, 0));
        assertEquals(SectionMath.EMPTY_SLOT, reg.overlayGet(world, 0, 16, 0));
    }

    @Test
    public void clearBufferedOfFlushedChunkIsANoop() {
        //A classic write to a position whose chunk was already streamed out
        //finds nothing pending: plain world write, no stale state anywhere
        JobBufferRegistry reg = streamingRegistry(1, 1000);
        IWorld world = aweWorld("world");
        IPlayerEntry p = player(new UUID(21, 8));
        FakeJob job = new FakeJob();

        buffer(reg, p, 1, job.proxy, world, 0, 0, 0, 41, 0);
        buffer(reg, p, 1, job.proxy, world, 0, 16, 0, 42, 0);
        reg.drainRoundRobin(NO_LIMIT, new RecordingSink(), false);
        assertEquals(0, reg.getBufferedCount(p));

        reg.clearBuffered(world, 0, 0, 0);
        //No counter went negative, nothing resurfaced
        assertEquals(0, reg.getBufferedCount(p));
        assertFalse(reg.hasWork());
    }

    @Test
    public void forcedFullFlushIgnoresTheWindow() {
        //A still-producing job under the watermark and not stale: force
        //flushes everything (shutdown / reload / demanding semantics are
        //unchanged by streaming)
        JobBufferRegistry reg = streamingRegistry(1000, 1000);
        IWorld world = aweWorld("world");
        IPlayerEntry p = player(new UUID(21, 9));
        FakeJob job = new FakeJob();

        buffer(reg, p, 1, job.proxy, world, 0, 64, 0, 1, 0);
        buffer(reg, p, 1, job.proxy, world, 16, 64, 0, 2, 0);

        RecordingSink sink = new RecordingSink();
        reg.forceFlush(sink);
        assertEquals(2, sink.order.size());
        assertFalse(reg.hasWork());
    }

    @Test
    public void streamingDisabledKeepsJobEndSemantics() {
        JobBufferRegistry reg = new JobBufferRegistry();
        reg.configureStream(false, 1, 1);
        IWorld world = aweWorld("world");
        IPlayerEntry p = player(new UUID(21, 10));
        FakeJob job = new FakeJob();

        //Over any window and instantly stale - but streaming is off
        buffer(reg, p, 1, job.proxy, world, 0, 0, 0, 1, 0);
        buffer(reg, p, 1, job.proxy, world, 0, 16, 0, 2, 0);
        buffer(reg, p, 1, job.proxy, world, 16, 64, 0, 3, 0);

        RecordingSink sink = new RecordingSink();
        reg.drainRoundRobin(NO_LIMIT, sink, false);
        reg.drainRoundRobin(NO_LIMIT, sink, false);
        assertEquals(0, sink.order.size());
        assertTrue(reg.hasWork());

        job.finish();
        reg.drainRoundRobin(NO_LIMIT, sink, false);
        assertEquals(3, sink.order.size());
        assertEquals(0, reg.getBufferCount());
    }

    @Test
    public void budgetLimitedStreamingCarriesOver() {
        //Window of 1 with three single-section chunks: two must stream out,
        //but the budget grants one chunk per run. The selection is
        //recomputed from the live watermark every run, so the cut tail
        //reappears until the job fits the window again.
        JobBufferRegistry reg = streamingRegistry(1, 1000);
        IWorld world = aweWorld("world");
        IPlayerEntry p = player(new UUID(21, 11));
        FakeJob job = new FakeJob();

        buffer(reg, p, 1, job.proxy, world, 0, 64, 0, 1, 0);    //A
        buffer(reg, p, 1, job.proxy, world, 16, 64, 0, 2, 0);   //B
        buffer(reg, p, 1, job.proxy, world, 32, 64, 0, 3, 0);   //C

        RecordingSink sink = new RecordingSink();
        //Run 1: A (the least recently written) flushes, budget stops the pass
        reg.drainRoundRobin(ONE_CHUNK, sink, false);
        assertEquals(1, sink.flushedChunkKeys.size());
        assertEquals(Long.valueOf(SectionMath.chunkKey(0, 0)), sink.flushedChunkKeys.get(0));
        assertEquals(1, reg.getLastStreamedChunks());
        assertEquals(2, reg.getLastCarriedChunks());

        //Run 2: still one over the window - B flushes
        reg.drainRoundRobin(ONE_CHUNK, sink, false);
        assertEquals(2, sink.flushedChunkKeys.size());
        assertEquals(Long.valueOf(SectionMath.chunkKey(1, 0)), sink.flushedChunkKeys.get(1));

        //Run 3: the job fits the window - nothing streams
        reg.drainRoundRobin(ONE_CHUNK, sink, false);
        assertEquals(2, sink.flushedChunkKeys.size());
        assertEquals(0, reg.getLastStreamedChunks());
        assertTrue(reg.hasWork());
    }
}
