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
package org.primesoft.asyncworldedit.worldedit;

import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.Vector;
import com.sk89q.worldedit.blocks.BaseBlock;
import com.sk89q.worldedit.world.World;
import org.primesoft.asyncworldedit.api.IWorld;
import org.primesoft.asyncworldedit.api.playerManager.IPlayerEntry;
import org.primesoft.asyncworldedit.api.worldedit.IThreadSafeEditSession;
import org.primesoft.asyncworldedit.chunkbatch.BatchEligibility;
import org.primesoft.asyncworldedit.chunkbatch.ChunkBatchWriter;
import org.primesoft.asyncworldedit.chunkbatch.JobBufferRegistry;
import org.primesoft.asyncworldedit.chunkbatch.SectionMath;
import org.primesoft.asyncworldedit.configuration.BHLevel;
import org.primesoft.asyncworldedit.configuration.ConfigEngine;
import org.primesoft.asyncworldedit.configuration.ConfigProvider;
import org.primesoft.asyncworldedit.worldedit.history.changeset.IColumnarRunSource;
import org.primesoft.asyncworldedit.worldedit.world.AbstractWorldWrapper;

/**
 * The undo/redo half of the fast lane: lowers columnar RLE runs (a
 * contiguous slot interval + one constant value, see
 * {@link IColumnarRunSource}) directly into bulk buffer fills of the
 * session player's LOOSE job buffer (job id -1) - the SAME buffer the
 * replay's per block writes land in (unwrapped vectors extract job id -1
 * and the AsyncWorld player), so the global write sequence keeps
 * last-write-wins between bulk-filled runs and any per block change of
 * the same replay, and the loose buffer streams out every placer run
 * exactly like today's per block replay.
 *
 * Undo-of-undo stays impossible by construction: loose ids are never
 * registered with the columnar undo registry, so the fills are never
 * captured (the same argument that protects the per block replay).
 *
 * A refused run (ineligible value, blacklist hit, budget stall) falls
 * back to the per-change iterator - never worse than the per block
 * replay, which enforces the same rules per position.
 *
 * @author KAMKEEL
 */
public final class ColumnarRunReplay {

    /**
     * Give up on a budget-stalled run after this long off the main thread
     * (the drain frees loose-buffer sections every placer run, so a real
     * stall means the server is not ticking - fall back per block)
     */
    private static final long STALL_ABORT_MS = 10000;

    private ColumnarRunReplay() {
    }

    /**
     * Build the bulk run consumer for one undo/redo replay, or null when
     * the fast lane cannot serve it (strict whitelist - any miss means
     * the whole replay runs per change exactly as before).
     *
     * @param parent the history-owning session (player, worlds, core)
     * @param session the write-target session of the replay
     * @param redo false = undo (restore old values), true = redo (restore
     * new values)
     */
    public static IColumnarRunSource.IRunConsumer bulkConsumer(
            IThreadSafeEditSession parent, EditSession session, boolean redo) {
        if (!(parent instanceof ThreadSafeEditSession)) {
            return null;
        }
        final ThreadSafeEditSession tse = (ThreadSafeEditSession) parent;

        final ConfigEngine engine = ConfigProvider.engine();
        if (engine == null || !engine.isFastLane()
                || !ConfigProvider.isBufferedEngine()
                || ConfigProvider.blocksHub().getLogBlocks() != BHLevel.Disabled) {
            return null;
        }
        //isActive() only reads volatiles - safe from the replay thread
        //(unlike the probing isDirectAvailable, which touches Bukkit)
        if (!ChunkBatchWriter.getInstance().isActive()) {
            return null;
        }
        //A survival block bag must account for every block - per block only
        if (session != null && session.getBlockBag() != null) {
            return null;
        }

        final IWorld aweWorld = tse.getCBWorld();
        if (aweWorld == null) {
            return null;
        }
        final World world = tse.getWorld();
        final World weWorld = world instanceof AbstractWorldWrapper
                ? ((AbstractWorldWrapper) world).getWorld() : world;
        if (weWorld == null) {
            return null;
        }

        final IPlayerEntry player = tse.getPlayer();
        //Sleeping in the budget retry loop is only allowed off the main
        //thread: on the main thread the drain cannot run while we wait
        final boolean mainThread = tse.m_aweCore.getTaskDispatcher().isMainTask();
        final JobBufferRegistry registry = JobBufferRegistry.getInstance();

        return new IColumnarRunSource.IRunConsumer() {
            @Override
            public boolean run(final int bx, final int by, final int bz,
                    int startSlot, int len,
                    int oldId, int oldData, int newId, int newData) {
                final int id = redo ? newId : oldId;
                final int data = redo ? newData : oldData;

                if (!BatchEligibility.isBatchable(id, data, by)) {
                    return false;
                }
                //One blacklist/permission check per constant-value run;
                //the per block fallback enforces the same rule per
                //position, so a refusal here loses nothing
                final BaseBlock block = new BaseBlock(id, data);
                if (!tse.m_aweCore.getBlocksHubBridge().canPlace(player,
                        aweWorld, new Vector(bx, by, bz), block, block)) {
                    return false;
                }

                return SectionMath.intervalToBoxes(startSlot, len,
                        new SectionMath.IIntervalBoxVisitor() {
                    @Override
                    public boolean box(int x0, int y0, int z0, int x1, int y1, int z1) {
                        return fillBox(bx + x0, by + y0, bz + z0,
                                bx + x1, by + y1, bz + z1);
                    }

                    private boolean fillBox(int x0, int y0, int z0,
                            int x1, int y1, int z1) {
                        long stalledSince = 0;
                        for (;;) {
                            final int r = registry.fillChunkBox(player, -1,
                                    weWorld, aweWorld, null,
                                    x0, y0, z0, x1, y1, z1, id, data, false);
                            if (r >= 0) {
                                return true;
                            }
                            //Budget refusal: backpressure. Boxes already
                            //filled stay buffered - the per block fallback
                            //rewrites the whole run remainder with later
                            //sequences over the same values, so a partial
                            //abort is harmless.
                            if (mainThread) {
                                return false;
                            }
                            final long now = System.currentTimeMillis();
                            if (stalledSince == 0) {
                                stalledSince = now;
                            } else if (now - stalledSince > STALL_ABORT_MS) {
                                return false;
                            }
                            try {
                                Thread.sleep(25);
                            } catch (InterruptedException ex) {
                                Thread.currentThread().interrupt();
                                return false;
                            }
                        }
                    }
                });
            }
        };
    }
}
