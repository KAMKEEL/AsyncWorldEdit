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
import java.util.UUID;
import static org.junit.Assert.*;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.primesoft.asyncworldedit.api.IWorld;
import org.primesoft.asyncworldedit.api.blockPlacer.entries.IJobEntry;
import org.primesoft.asyncworldedit.api.playerManager.IPlayerEntry;

/**
 * Tests for the //replace fast lane's bulk production entry point:
 * conditional stores, the overlay EMPTY contract on the producer thread,
 * budget refusal vs condition conflict return codes and their rollbacks.
 *
 * @author KAMKEEL
 */
public class JobBufferReplaceTest {

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
        return (World) Proxy.newProxyInstance(JobBufferReplaceTest.class.getClassLoader(),
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
        return (IWorld) Proxy.newProxyInstance(JobBufferReplaceTest.class.getClassLoader(),
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
        return (IPlayerEntry) Proxy.newProxyInstance(JobBufferReplaceTest.class.getClassLoader(),
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
        return (IJobEntry) Proxy.newProxyInstance(JobBufferReplaceTest.class.getClassLoader(),
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
    public void replaceStoresConditionallyAndTheOverlayReadsEmpty() {
        JobBufferRegistry reg = new JobBufferRegistry();
        IPlayerEntry p = player(new UUID(1, 1));
        IWorld world = aweWorld();

        int r = reg.replaceChunkBox(p, 1, weWorld(), world, doneJob(),
                0, 10, 0, 7, 12, 7, 42, 5, false, 3, -1);
        assertEquals(8 * 3 * 8, r);
        assertEquals(8 * 3 * 8, reg.getBufferedCount(p));
        assertEquals(1, SectionBudget.getShared().getUsed());

        //Overlay contract: a conditional slot is unknowable until flush -
        //the producer thread's read-your-writes must MISS so the read
        //falls through to the world (pinned here because //replace on
        //WE 6 must see pre-replace world values while compiling)
        assertEquals(SectionMath.EMPTY_SLOT, reg.overlayGet(world, 1, 11, 1));

        //An unconditional fill at the same position IS visible
        assertTrue(reg.fillChunkBox(p, 1, weWorld(), world, doneJob(),
                1, 11, 1, 1, 11, 1, 7, 0, false) >= 0);
        int slot = reg.overlayGet(world, 1, 11, 1);
        assertEquals(7, SectionMath.slotId(slot));
    }

    @Test
    public void budgetRefusalReturnsMinusOneAndRollsBack() {
        SectionBudget.getShared().setMaxSections(1);
        JobBufferRegistry reg = new JobBufferRegistry();
        IPlayerEntry p = player(new UUID(2, 2));

        //Needs two new sections, only one budget slot exists
        int r = reg.replaceChunkBox(p, 1, weWorld(), aweWorld(), doneJob(),
                0, 0, 0, 15, 31, 15, 1, 0, false, 3, -1);
        assertEquals(-1, r);
        assertEquals(0, reg.getBufferedCount(p));
        assertEquals(0, SectionBudget.getShared().getUsed());
    }

    @Test
    public void conditionConflictReturnsMinusTwoAndRollsBackTheBudget() {
        JobBufferRegistry reg = new JobBufferRegistry();
        IPlayerEntry p = player(new UUID(3, 3));

        assertTrue(reg.replaceChunkBox(p, 1, weWorld(), aweWorld(), doneJob(),
                0, 0, 0, 3, 3, 3, 9, 0, false, 5, 0) > 0);
        int used = SectionBudget.getShared().getUsed();
        int queued = reg.getBufferedCount(p);

        //Same section, different condition: -2, nothing stored, the
        //(zero) newly acquired budget stays balanced
        int r = reg.replaceChunkBox(p, 1, weWorld(), aweWorld(), doneJob(),
                8, 0, 8, 11, 3, 11, 9, 0, false, 6, 0);
        assertEquals(-2, r);
        assertEquals(used, SectionBudget.getShared().getUsed());
        assertEquals(queued, reg.getBufferedCount(p));

        //A conflicting fill that would have allocated a NEW section must
        //give that budget slot back
        int usedBefore = SectionBudget.getShared().getUsed();
        r = reg.replaceChunkBox(p, 1, weWorld(), aweWorld(), doneJob(),
                0, 0, 0, 3, 30, 3, 9, 0, false, 6, 0);
        assertEquals(-2, r);
        assertEquals(usedBefore, SectionBudget.getShared().getUsed());
    }
}
