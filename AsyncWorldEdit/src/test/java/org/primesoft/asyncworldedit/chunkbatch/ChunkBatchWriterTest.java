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

import com.sk89q.worldedit.Vector;
import com.sk89q.worldedit.blocks.BaseBlock;
import com.sk89q.worldedit.world.World;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import static org.junit.Assert.*;
import org.junit.Test;
import org.primesoft.asyncworldedit.api.IChunk;
import org.primesoft.asyncworldedit.api.IWorld;
import org.primesoft.asyncworldedit.blockPlacer.AdaptiveTickBudget;
import org.primesoft.asyncworldedit.chunkbatch.nms.NmsChunkWriter;
import org.primesoft.asyncworldedit.platform.bukkit.IBukkitWorld;

/**
 * Pure logic tests for the chunk batch writer: read-your-writes overlay,
 * clearPending on classic path fallbacks, per chunk exception isolation
 * during flush and the budget limited flush with carry over.
 *
 * The WorldEdit parent world is a dynamic proxy that records every
 * classic setBlock; the batches stay below the direct write threshold so
 * everything flushes through the (pure) classic replay path.
 *
 * @author KAMKEEL
 */
public class ChunkBatchWriterTest {

    /**
     * Recording WorldEdit world stub. Can be told to throw for blocks
     * inside one chunk to test flush exception isolation.
     */
    private static class ParentWorld implements InvocationHandler {

        final List<String> placed = new ArrayList<String>();

        /**
         * Chunk (x >> 4) whose writes throw a RuntimeException, null for
         * none
         */
        Integer failingChunkX = null;

        final World proxy;

        ParentWorld() {
            proxy = (World) Proxy.newProxyInstance(
                    ChunkBatchWriterTest.class.getClassLoader(),
                    new Class<?>[]{World.class}, this);
        }

        @Override
        public Object invoke(Object o, Method method, Object[] args) {
            String name = method.getName();

            if ("setBlock".equals(name) && args.length >= 2 && args[0] instanceof Vector) {
                Vector v = (Vector) args[0];
                BaseBlock b = (BaseBlock) args[1];
                if (failingChunkX != null
                        && (v.getBlockX() >> 4) == failingChunkX.intValue()) {
                    throw new RuntimeException("injected chunk failure");
                }
                placed.add(v.getBlockX() + "," + v.getBlockY() + "," + v.getBlockZ()
                        + ":" + b.getType() + ":" + b.getData());
                return Boolean.TRUE;
            }
            if ("getBlock".equals(name) || "getLazyBlock".equals(name)) {
                return new BaseBlock(0);
            }
            if ("getBlockType".equals(name) || "getBlockData".equals(name)) {
                return 0;
            }
            if ("getName".equals(name)) {
                return "world";
            }

            Class<?> rt = method.getReturnType();
            if (rt == boolean.class) {
                return Boolean.FALSE;
            }
            if (rt == int.class) {
                return 0;
            }
            return null;
        }
    }

    /**
     * Minimal AWE world stub; the Bukkit world behind it is a proxy that
     * must never be touched on the classic flush path
     */
    private static class StubWorld implements IWorld, IBukkitWorld {

        private final org.bukkit.World m_bukkit = (org.bukkit.World) Proxy.newProxyInstance(
                ChunkBatchWriterTest.class.getClassLoader(),
                new Class<?>[]{org.bukkit.World.class}, new InvocationHandler() {
            @Override
            public Object invoke(Object o, Method method, Object[] args) {
                throw new UnsupportedOperationException(
                        "the classic flush path must not touch the Bukkit world ("
                        + method.getName() + ")");
            }
        });

        @Override
        public UUID getUUID() {
            return new UUID(0, 0);
        }

        @Override
        public String getName() {
            return "world";
        }

        @Override
        public void regenerateChunk(int cx, int cz) {
        }

        @Override
        public boolean isChunkLoaded(int cx, int cz) {
            return true;
        }

        @Override
        public IChunk getChunkAt(int cx, int cz) {
            return null;
        }

        @Override
        public org.bukkit.World getWorld() {
            return m_bukkit;
        }
    }

    /**
     * A writer instance in an open window, min blocks set high so every
     * chunk flushes through the classic path
     */
    private static ChunkBatchWriter openWriter() {
        ChunkBatchWriter writer = new ChunkBatchWriter();
        writer.configureForTest(1000, new NmsChunkWriter(null));
        writer.openWindow();
        return writer;
    }

    private static boolean batch(ChunkBatchWriter writer, ParentWorld parent,
            StubWorld world, int x, int y, int z, int id, int data) {
        return writer.trySetBlock(parent.proxy, world, new Vector(x, y, z),
                new BaseBlock(id, data), true);
    }

    @Test
    public void overlayServesPendingBlockThroughAllReadApis() {
        ParentWorld parent = new ParentWorld();
        StubWorld world = new StubWorld();
        ChunkBatchWriter writer = openWriter();

        assertTrue(batch(writer, parent, world, 5, 64, 5, 89, 3));

        Vector p = new Vector(5, 64, 5);
        assertEquals(89, writer.getBlock(parent.proxy, world, p).getType());
        assertEquals(3, writer.getBlock(parent.proxy, world, p).getData());
        assertEquals(89, writer.getBlockType(parent.proxy, world, p));
        assertEquals(3, writer.getBlockData(parent.proxy, world, p));
        assertEquals(89, writer.getLazyBlock(parent.proxy, world, p).getType());

        //Positions without a pending block fall through to the parent
        Vector q = new Vector(6, 64, 5);
        assertEquals(0, writer.getBlock(parent.proxy, world, q).getType());
        assertEquals(0, writer.getBlockType(parent.proxy, world, q));
    }

    @Test
    public void classicFlushReplaysInInsertionOrder() throws Exception {
        ParentWorld parent = new ParentWorld();
        StubWorld world = new StubWorld();
        ChunkBatchWriter writer = openWriter();

        //Support (high y) queued first, attachment (low y) after it - the
        //classic replay must keep that order, not the coordinate order
        assertTrue(batch(writer, parent, world, 5, 70, 5, 4, 0));
        assertTrue(batch(writer, parent, world, 5, 64, 5, 50, 1));

        writer.closeWindow();

        assertEquals(2, parent.placed.size());
        assertEquals("5,70,5:4:0", parent.placed.get(0));
        assertEquals("5,64,5:50:1", parent.placed.get(1));
    }

    @Test
    public void clearPendingRemovesOverlayAndFlushSkipsPosition() {
        ParentWorld parent = new ParentWorld();
        StubWorld world = new StubWorld();
        ChunkBatchWriter writer = openWriter();

        //Batch a stone at P, then a classic path write happens at P (e.g.
        //a chest with NBT): the stale pending stone must vanish
        assertTrue(batch(writer, parent, world, 1, 64, 1, 1, 0));
        assertTrue(batch(writer, parent, world, 2, 64, 2, 20, 0));

        writer.clearPending(world, new Vector(1, 64, 1));

        //Overlay miss: the read must come from the world again
        assertEquals(0, writer.getBlockType(parent.proxy, world, new Vector(1, 64, 1)));

        writer.closeWindow();

        //The flush must not write P over the classic block
        assertEquals(1, parent.placed.size());
        assertEquals("2,64,2:20:0", parent.placed.get(0));
        assertFalse(writer.hasCarryOver());
    }

    @Test
    public void flushIsolatesFailingChunkAndEndsEmpty() {
        ParentWorld parent = new ParentWorld();
        StubWorld world = new StubWorld();
        ChunkBatchWriter writer = openWriter();

        //Chunk 0 fails during classic replay, chunk 1 must still flush
        assertTrue(batch(writer, parent, world, 1, 64, 1, 1, 0));
        assertTrue(batch(writer, parent, world, 17, 64, 1, 2, 0));
        parent.failingChunkX = 0;

        //No exception may escape the window close
        writer.closeWindow();

        assertEquals(1, parent.placed.size());
        assertEquals("17,64,1:2:0", parent.placed.get(0));
        //The failing chunk must not survive to the next window
        assertFalse(writer.hasCarryOver());
    }

    @Test
    public void budgetLimitedFlushCarriesOverAndKeepsOverlayAlive() {
        ParentWorld parent = new ParentWorld();
        StubWorld world = new StubWorld();
        ChunkBatchWriter writer = openWriter();

        assertTrue(batch(writer, parent, world, 1, 64, 1, 1, 0));
        assertTrue(batch(writer, parent, world, 17, 64, 1, 2, 0));

        //Budget already used up: exactly one chunk still flushes (carry
        //over must never starve), the second carries over
        writer.closeWindow(new ChunkBatchWriter.IFlushLimit() {
            @Override
            public boolean shouldContinue() {
                return false;
            }
        });

        assertEquals(1, parent.placed.size());
        assertEquals("1,64,1:1:0", parent.placed.get(0));
        assertTrue(writer.hasCarryOver());

        //The read overlay of the carried chunk stays alive between runs
        assertEquals(2, writer.getBlockType(parent.proxy, world, new Vector(17, 64, 1)));
        //...and classic writes between runs still clear stale positions
        writer.clearPending(world, new Vector(17, 64, 1));
        assertEquals(0, writer.getBlockType(parent.proxy, world, new Vector(17, 64, 1)));
        assertFalse(writer.hasCarryOver());
    }

    @Test
    public void carriedChunksFlushOnTheNextRun() {
        ParentWorld parent = new ParentWorld();
        StubWorld world = new StubWorld();
        ChunkBatchWriter writer = openWriter();

        //Three chunks, driven by a real AdaptiveTickBudget on a fake
        //clock: the budget is exhausted, so run one flushes only the
        //mandatory first chunk
        assertTrue(batch(writer, parent, world, 1, 64, 1, 1, 0));
        assertTrue(batch(writer, parent, world, 17, 64, 1, 2, 0));
        assertTrue(batch(writer, parent, world, 33, 64, 1, 3, 0));

        final AdaptiveTickBudget budget = new AdaptiveTickBudget(18, 20, 0, 2, 1, 4);
        budget.onTickStart(0);
        //Fake clock: 25 ms already elapsed, over the 20 ms base budget
        final long elapsedNanos = 25L * 1000000L;

        writer.closeWindow(new ChunkBatchWriter.IFlushLimit() {
            @Override
            public boolean shouldContinue() {
                return budget.shouldContinue(elapsedNanos);
            }
        });

        assertEquals(1, parent.placed.size());
        assertTrue(writer.hasCarryOver());

        //Next placer run with time allowance left: the rest flushes
        writer.openWindow();
        writer.closeWindow();

        assertEquals(3, parent.placed.size());
        assertEquals("17,64,1:2:0", parent.placed.get(1));
        assertEquals("33,64,1:3:0", parent.placed.get(2));
        assertFalse(writer.hasCarryOver());
    }

    @Test
    public void forceFlushWritesCarriedBatchesWithoutAWindow() {
        ParentWorld parent = new ParentWorld();
        StubWorld world = new StubWorld();
        ChunkBatchWriter writer = openWriter();

        assertTrue(batch(writer, parent, world, 1, 64, 1, 1, 0));
        assertTrue(batch(writer, parent, world, 17, 64, 1, 2, 0));

        writer.closeWindow(new ChunkBatchWriter.IFlushLimit() {
            @Override
            public boolean shouldContinue() {
                return false;
            }
        });
        assertTrue(writer.hasCarryOver());

        //Shutdown / reload: no window is open, the carried chunk must be
        //fully flushed regardless of any budget so nothing is ever lost
        writer.forceFlush();

        assertEquals(2, parent.placed.size());
        assertFalse(writer.hasCarryOver());

        //Idempotent when there is nothing left
        writer.forceFlush();
        assertEquals(2, parent.placed.size());
    }
}
