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
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import static org.junit.Assert.*;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.primesoft.asyncworldedit.api.IWorld;
import org.primesoft.asyncworldedit.api.blockPlacer.entries.IJobEntry;
import org.primesoft.asyncworldedit.api.playerManager.IPlayerEntry;
import org.primesoft.asyncworldedit.chunkbatch.undo.ICaptureSink;

/**
 * Tests for the fast lane's bulk production entry point: budget
 * acquisition and rollback, counters, discard, and that a filled buffer
 * flushes the constant values.
 *
 * @author KAMKEEL
 */
public class JobBufferFillTest {

    @Before
    @After
    public void resetBudget() {
        SectionBudget.getShared().release(Integer.MAX_VALUE);
        SectionBudget.getShared().setMaxSections(SectionBudget.DEFAULT_MAX_SECTIONS);
    }

    private static Object defaultValue(Method m) {
        Class<?> r = m.getReturnType();
        if (r == boolean.class) {
            return Boolean.FALSE;
        }
        if (r == int.class) {
            return Integer.valueOf(0);
        }
        if (r == long.class) {
            return Long.valueOf(0);
        }
        if (r == double.class) {
            return Double.valueOf(0);
        }
        return null;
    }

    private static World weWorld() {
        return (World) Proxy.newProxyInstance(JobBufferFillTest.class.getClassLoader(),
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

    private static IWorld aweWorld() {
        return (IWorld) Proxy.newProxyInstance(JobBufferFillTest.class.getClassLoader(),
                new Class<?>[]{IWorld.class}, new InvocationHandler() {
            @Override
            public Object invoke(Object o, Method m, Object[] a) {
                if ("getName".equals(m.getName())) {
                    return "world";
                }
                return defaultValue(m);
            }
        });
    }

    private static IPlayerEntry player(final UUID uuid) {
        return (IPlayerEntry) Proxy.newProxyInstance(JobBufferFillTest.class.getClassLoader(),
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

    private static IJobEntry doneJob() {
        return (IJobEntry) Proxy.newProxyInstance(JobBufferFillTest.class.getClassLoader(),
                new Class<?>[]{IJobEntry.class}, new InvocationHandler() {
            @Override
            public Object invoke(Object o, Method m, Object[] a) {
                if ("isTaskDone".equals(m.getName())) {
                    return Boolean.TRUE;
                }
                return defaultValue(m);
            }
        });
    }

    @Test
    public void fillStoresTheBoxAndFlushesTheConstant() {
        JobBufferRegistry reg = new JobBufferRegistry();
        IPlayerEntry p = player(new UUID(1, 1));

        int r = reg.fillChunkBox(p, 1, weWorld(), aweWorld(), doneJob(),
                2, 10, 2, 13, 25, 13, 42, 5, false);
        assertEquals(12 * 16 * 12, r);
        assertEquals(12 * 16 * 12, reg.getBufferedCount(p));
        assertEquals(2, SectionBudget.getShared().getUsed());

        final Map<String, int[]> flushed = new ConcurrentHashMap<String, int[]>();
        reg.forceFlush(new JobBufferRegistry.IFlushSink() {
            @Override
            public void flush(World weWorld, IWorld aweWorld, PendingChunk chunk,
                    ICaptureSink captureSink) {
                chunk.forEachLastWriteOrder(new PendingChunk.IPendingBlockVisitor() {
                    @Override
                    public void visit(int x, int y, int z, int id, int data,
                            boolean notify, int seq) {
                        flushed.put(x + "," + y + "," + z, new int[]{id, data});
                    }
                });
            }
        });

        assertEquals(12 * 16 * 12, flushed.size());
        int[] corner = flushed.get("2,10,2");
        assertNotNull(corner);
        assertEquals(42, corner[0]);
        assertEquals(5, corner[1]);
        assertNull(flushed.get("1,10,2"));
        assertNull(flushed.get("2,9,2"));
        //Everything drained: budget released, buffer pruned
        assertEquals(0, SectionBudget.getShared().getUsed());
        assertEquals(0, reg.getBufferCount());
    }

    @Test
    public void budgetRefusalStoresNothingAndRollsBackFully() {
        SectionBudget.getShared().setMaxSections(1);
        JobBufferRegistry reg = new JobBufferRegistry();
        IPlayerEntry p = player(new UUID(2, 2));

        //The box needs two new sections but only one budget slot exists
        int r = reg.fillChunkBox(p, 1, weWorld(), aweWorld(), doneJob(),
                0, 0, 0, 15, 31, 15, 1, 0, false);
        assertEquals(-1, r);
        assertEquals(0, reg.getBufferedCount(p));
        assertEquals(0, SectionBudget.getShared().getUsed());
    }

    @Test
    public void discardJobDropsBuffersAndReleasesTheBudget() {
        JobBufferRegistry reg = new JobBufferRegistry();
        UUID uuid = new UUID(3, 3);
        IPlayerEntry p = player(uuid);

        assertTrue(reg.fillChunkBox(p, 7, weWorld(), aweWorld(), doneJob(),
                0, 0, 0, 15, 15, 15, 1, 0, false) > 0);
        assertTrue(reg.fillChunkBox(p, 7, weWorld(), aweWorld(), doneJob(),
                16, 0, 0, 31, 15, 15, 1, 0, false) > 0);
        assertEquals(2, SectionBudget.getShared().getUsed());
        assertTrue(reg.hasBuffer(uuid, 7));

        reg.discardJob(uuid, 7);

        assertFalse(reg.hasBuffer(uuid, 7));
        assertEquals(0, SectionBudget.getShared().getUsed());
        assertEquals(0, reg.getBufferedCount(p));
    }

    @Test
    public void laneReflectsHowTheBufferWasProduced() {
        JobBufferRegistry reg = new JobBufferRegistry();
        UUID uuid = new UUID(5, 5);
        IPlayerEntry p = player(uuid);
        IWorld world = aweWorld();

        //Only bulk fills: lane=fast
        assertTrue(reg.fillChunkBox(p, 1, weWorld(), world, doneJob(),
                0, 0, 0, 3, 3, 3, 1, 0, false) > 0);
        assertEquals("fast", reg.getBuffer(uuid, 1).getLane());

        //Only per block writes: lane=blocks
        assertTrue(reg.buffer(p, 2, weWorld(), world, doneJob(),
                0, 0, 0, 9, 0, false));
        assertEquals("blocks", reg.getBuffer(uuid, 2).getLane());

        //Both: lane=mixed
        assertTrue(reg.buffer(p, 1, weWorld(), world, doneJob(),
                8, 8, 8, 9, 0, false));
        assertEquals("mixed", reg.getBuffer(uuid, 1).getLane());
    }

    @Test
    public void fillsAndSingleWritesShareTheGlobalSequence() {
        JobBufferRegistry reg = new JobBufferRegistry();
        IPlayerEntry p = player(new UUID(4, 4));
        IWorld world = aweWorld();

        assertTrue(reg.buffer(p, 1, weWorld(), world, doneJob(),
                0, 0, 0, 9, 0, true));
        long seqAfterSingle = reg.getWriteSeq();
        assertTrue(reg.fillChunkBox(p, 1, weWorld(), world, doneJob(),
                0, 1, 0, 3, 1, 3, 5, 0, false) > 0);
        //The fill consumed exactly one global sequence number
        assertEquals(seqAfterSingle + 1, reg.getWriteSeq());
    }
}
