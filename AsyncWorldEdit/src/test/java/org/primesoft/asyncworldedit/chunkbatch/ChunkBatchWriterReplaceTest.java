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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.junit.Assert.*;
import org.junit.Test;
import org.primesoft.asyncworldedit.api.IChunk;
import org.primesoft.asyncworldedit.api.IWorld;
import org.primesoft.asyncworldedit.chunkbatch.nms.NmsChunkWriter;
import org.primesoft.asyncworldedit.chunkbatch.undo.ICaptureSink;
import org.primesoft.asyncworldedit.platform.bukkit.IBukkitWorld;

/**
 * End-to-end flush tests for conditional (//replace fast lane) chunks
 * through {@link ChunkBatchWriter#flushJobChunk}: the pre-write world
 * decides the matches, only matches are written by the classic replay,
 * and the undo capture sink NEVER sees a non-matching slot.
 *
 * @author KAMKEEL
 */
public class ChunkBatchWriterReplaceTest {

    /**
     * WorldEdit world stub over a position -> block map: reads return the
     * mapped block (air otherwise, unreadable positions throw), writes
     * record into placed
     */
    private static class FakeWorld implements InvocationHandler {

        final Map<String, int[]> blocks = new HashMap<String, int[]>();
        final List<String> unreadable = new ArrayList<String>();
        final Map<String, int[]> placed = new HashMap<String, int[]>();

        final World proxy;

        FakeWorld() {
            proxy = (World) Proxy.newProxyInstance(
                    ChunkBatchWriterReplaceTest.class.getClassLoader(),
                    new Class<?>[]{World.class}, this);
        }

        @Override
        public Object invoke(Object o, Method method, Object[] args) {
            String name = method.getName();

            if ("setBlock".equals(name) && args.length >= 2 && args[0] instanceof Vector) {
                Vector v = (Vector) args[0];
                BaseBlock b = (BaseBlock) args[1];
                placed.put(v.getBlockX() + "," + v.getBlockY() + "," + v.getBlockZ(),
                        new int[]{b.getType(), b.getData()});
                return Boolean.TRUE;
            }
            if ("getBlock".equals(name) || "getLazyBlock".equals(name)) {
                Vector v = (Vector) args[0];
                String key = v.getBlockX() + "," + v.getBlockY() + "," + v.getBlockZ();
                if (unreadable.contains(key)) {
                    throw new RuntimeException("injected unreadable position");
                }
                int[] b = blocks.get(key);
                return b == null ? new BaseBlock(0) : new BaseBlock(b[0], b[1]);
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
     * Minimal AWE world; its Bukkit world proxy throws on every call so
     * the raw reader construction fails over to the parent world reads
     */
    private static class StubWorld implements IWorld, IBukkitWorld {

        private final org.bukkit.World m_bukkit = (org.bukkit.World) Proxy.newProxyInstance(
                ChunkBatchWriterReplaceTest.class.getClassLoader(),
                new Class<?>[]{org.bukkit.World.class}, new InvocationHandler() {
            @Override
            public Object invoke(Object o, Method method, Object[] args) {
                throw new UnsupportedOperationException(
                        "no NMS access in this test (" + method.getName() + ")");
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
     * Recording capture sink
     */
    private static class RecordingSink implements ICaptureSink {

        final List<String> captured = new ArrayList<String>();
        final List<Integer> missing = new ArrayList<Integer>();

        @Override
        public void beginSection(int cx, int cz, int section, int firstSeq, int lastSeq) {
        }

        @Override
        public void capture(int slotIndex, int oldId, int oldData, int newId, int newData) {
            captured.add(slotIndex + ":" + oldId + ":" + oldData
                    + "->" + newId + ":" + newData);
        }

        @Override
        public void markMissing(int slotIndex) {
            missing.add(Integer.valueOf(slotIndex));
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
        }
    }

    @Test
    public void flushWritesAndCapturesOnlyTheMatches() {
        FakeWorld world = new FakeWorld();
        //The world: 5:2 at (0,0,0) and (1,0,0), 7:1 at (0,0,1),
        //unreadable at (1,0,1), air everywhere else
        world.blocks.put("0,0,0", new int[]{5, 2});
        world.blocks.put("1,0,0", new int[]{5, 2});
        world.blocks.put("0,0,1", new int[]{7, 1});
        world.unreadable.add("1,0,1");

        PendingChunk chunk = new PendingChunk(0, 0);
        ////replace 5:2 -> 9:3 over a 2x1x2 box
        assertEquals(4, chunk.fillBoxConditional(0, 0, 0, 1, 0, 1,
                9, 3, false, 1, 5, 2));

        ChunkBatchWriter writer = new ChunkBatchWriter();
        //High threshold: the chunk flushes through the classic replay
        writer.configureForTest(1000, new NmsChunkWriter(null));

        RecordingSink sink = new RecordingSink();
        writer.flushJobChunk(world.proxy, new StubWorld(), chunk, sink);

        //Only the two matches were written
        assertEquals(2, world.placed.size());
        int[] a = world.placed.get("0,0,0");
        assertNotNull(a);
        assertEquals(9, a[0]);
        assertEquals(3, a[1]);
        assertNotNull(world.placed.get("1,0,0"));
        assertNull(world.placed.get("0,0,1"));
        assertNull(world.placed.get("1,0,1"));

        //The capture sink saw ONLY the matched slots, with exact old
        //values, and no missing marks (the unreadable slot was dropped
        //before capture)
        assertEquals(2, sink.captured.size());
        assertTrue(sink.captured.contains(
                SectionMath.sectionIndex(0, 0, 0) + ":5:2->9:3"));
        assertTrue(sink.captured.contains(
                SectionMath.sectionIndex(1, 0, 0) + ":5:2->9:3"));
        assertTrue(sink.missing.isEmpty());
    }

    @Test
    public void mixedConditionalAndPlainSlotsFlushTogether() {
        FakeWorld world = new FakeWorld();
        world.blocks.put("0,0,0", new int[]{5, 0});
        //(0,0,1) is air - the condition will not match there

        PendingChunk chunk = new PendingChunk(0, 0);
        //A plain fill and a conditional fill in the SAME section
        assertEquals(1, chunk.fillBox(3, 0, 3, 3, 0, 3, 2, 1, false, 1));
        assertEquals(2, chunk.fillBoxConditional(0, 0, 0, 0, 0, 1,
                9, 0, false, 2, 5, 0));

        ChunkBatchWriter writer = new ChunkBatchWriter();
        writer.configureForTest(1000, new NmsChunkWriter(null));

        RecordingSink sink = new RecordingSink();
        writer.flushJobChunk(world.proxy, new StubWorld(), chunk, sink);

        //The plain slot and the matching conditional slot were written
        assertEquals(2, world.placed.size());
        assertNotNull(world.placed.get("3,0,3"));
        assertNotNull(world.placed.get("0,0,0"));
        assertNull(world.placed.get("0,0,1"));
        assertEquals(2, sink.captured.size());
    }
}
