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
import com.sk89q.worldedit.WorldEditException;
import com.sk89q.worldedit.blocks.BaseBlock;
import com.sk89q.worldedit.world.World;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.Bukkit;
import static org.primesoft.asyncworldedit.LoggerProvider.log;
import org.primesoft.asyncworldedit.api.IWorld;
import org.primesoft.asyncworldedit.chunkbatch.nms.NmsChunkWriter;
import org.primesoft.asyncworldedit.chunkbatch.nms.NmsHandles;
import org.primesoft.asyncworldedit.chunkbatch.nms.NmsProbe;
import org.primesoft.asyncworldedit.chunkbatch.nms.ProbeException;
import org.primesoft.asyncworldedit.platform.bukkit.IBukkitWorld;

/**
 * Groups queued block placements per chunk during a block placer run and
 * flushes them as direct chunk section writes.
 *
 * The batching window is opened and closed by the block placer around
 * processQueue on the server main thread. While the window is open,
 * AsyncWorld routes the final world write of every queued block through
 * {@link #trySetBlock}; batchable blocks (plain id + data, no NBT) are
 * collected here instead of being placed one by one through the Bukkit
 * API. All per block bookkeeping (BlocksHub canPlace/logging, job
 * counters, physics watcher) still runs in the queued entries exactly as
 * before - only the final world write is deferred to the end of the
 * placer run.
 *
 * {@link #getBlock} gives the entries read-your-writes semantics inside
 * the window: a read of a position with a pending block returns the
 * pending block, so same-position rewrites within one run keep sequential
 * behaviour.
 *
 * On flush, chunks with at least the configured minimum number of pending
 * blocks are written directly into the NMS chunk sections (one chunk
 * refresh packet per chunk); smaller batches and everything else fall
 * back to the classic per block path. If the NMS layout probe fails or a
 * direct write ever throws, the writer permanently falls back to classic
 * placement (one clear log line).
 *
 * @author KAMKEEL
 */
public class ChunkBatchWriter {

    private static final ChunkBatchWriter s_instance = new ChunkBatchWriter();

    public static ChunkBatchWriter getInstance() {
        return s_instance;
    }

    /**
     * Pending blocks of one world
     */
    private static class WorldBatch {

        /**
         * The WorldEdit world used for classic replay of small batches
         */
        final World parent;

        /**
         * The Bukkit world used for the direct NMS writes
         */
        final org.bukkit.World bukkit;

        final Map<Long, PendingChunk> chunks = new LinkedHashMap<Long, PendingChunk>();

        WorldBatch(World parent, org.bukkit.World bukkit) {
            this.parent = parent;
            this.bukkit = bukkit;
        }
    }

    /**
     * Enabled in the configuration
     */
    private volatile boolean m_enabled;

    /**
     * Minimum number of pending blocks in one chunk before the direct
     * path is used
     */
    private volatile int m_minBlocksPerChunk = 64;

    /**
     * The probe ran (successfully or not)
     */
    private volatile boolean m_probeAttempted;

    /**
     * The NMS writer, null when direct chunk mode is unavailable
     */
    private volatile NmsChunkWriter m_nmsWriter;

    /**
     * Permanently disabled after a runtime failure
     */
    private volatile boolean m_runtimeDisabled;

    /**
     * The thread that currently has the batching window open (the main
     * thread during a block placer run), null when closed. All batch
     * state is only touched by this thread.
     */
    private volatile Thread m_windowThread;

    /**
     * Pending blocks per world name, only touched inside the window
     */
    private final Map<String, WorldBatch> m_batches = new HashMap<String, WorldBatch>();

    /**
     * Classic replay error was logged already
     */
    private boolean m_replayErrorLogged;

    ChunkBatchWriter() {
    }

    /**
     * Apply the configuration. Called from the block placer on startup
     * and reload.
     */
    public void configure(boolean enabled, int minBlocksPerChunk) {
        m_minBlocksPerChunk = Math.max(1, minBlocksPerChunk);

        boolean wasEnabled = m_enabled;
        m_enabled = enabled;

        if (enabled) {
            probe();
        } else if (wasEnabled || !m_probeAttempted) {
            log("Direct chunk placement disabled in config, using classic block placement.");
        }
    }

    /**
     * True when direct chunk mode is operational
     */
    public boolean isActive() {
        return m_enabled && !m_runtimeDisabled && m_nmsWriter != null;
    }

    /**
     * Run the one time NMS capability probe. Retries silently while no
     * world is loaded yet; logs one clear line once it ran.
     */
    private void probe() {
        if (m_probeAttempted) {
            return;
        }

        List<org.bukkit.World> worlds;
        try {
            worlds = Bukkit.getWorlds();
        } catch (Throwable ex) {
            worlds = null;
        }
        if (worlds == null || worlds.isEmpty()) {
            //No world to probe yet - try again on the next window
            return;
        }

        m_probeAttempted = true;
        try {
            NmsHandles handles = NmsProbe.probe(worlds.get(0).getClass());
            m_nmsWriter = new NmsChunkWriter(handles);
            log(String.format(
                    "Direct chunk placement active (%s section layout, min %d blocks per chunk).",
                    handles.layout == NmsHandles.Layout.ID16
                            ? "NotEnoughIDs 16 bit" : "vanilla 12 bit",
                    m_minBlocksPerChunk));
        } catch (ProbeException ex) {
            log("Direct chunk placement not available: " + ex.getMessage()
                    + " - falling back to classic block placement.");
        } catch (Throwable ex) {
            log("Direct chunk placement not available: " + ex
                    + " - falling back to classic block placement.");
        }
    }

    /**
     * Open the batching window. Must be called from the main thread by
     * the block placer before it starts draining the queues.
     */
    public void openWindow() {
        if (m_enabled && !m_probeAttempted) {
            probe();
        }

        if (isActive()) {
            m_windowThread = Thread.currentThread();
        }
    }

    /**
     * Flush and close the batching window
     */
    public void closeWindow() {
        if (!isWindowOpenOnThisThread()) {
            return;
        }
        try {
            flush();
        } finally {
            m_windowThread = null;
        }
    }

    private boolean isWindowOpenOnThisThread() {
        return m_windowThread == Thread.currentThread();
    }

    /**
     * Try to batch a block placement. Returns false when the block can
     * not be batched (window closed, not batchable, unknown world); the
     * caller must then place it through the classic path.
     */
    public boolean trySetBlock(World parent, IWorld bukkitWorld, Vector location,
            BaseBlock block, boolean notify) {
        if (!isWindowOpenOnThisThread()
                || parent == null || bukkitWorld == null || location == null) {
            return false;
        }

        final int y = location.getBlockY();
        if (!BatchEligibility.isBatchable(block, y)) {
            return false;
        }

        WorldBatch batch = m_batches.get(bukkitWorld.getName());
        if (batch == null) {
            org.bukkit.World bukkit = resolveBukkitWorld(bukkitWorld);
            if (bukkit == null) {
                return false;
            }
            batch = new WorldBatch(parent, bukkit);
            m_batches.put(bukkitWorld.getName(), batch);
        }

        final int x = location.getBlockX();
        final int z = location.getBlockZ();
        final long key = SectionMath.chunkKey(
                SectionMath.blockToChunk(x), SectionMath.blockToChunk(z));

        PendingChunk chunk = batch.chunks.get(key);
        if (chunk == null) {
            chunk = new PendingChunk(
                    SectionMath.blockToChunk(x), SectionMath.blockToChunk(z));
            batch.chunks.put(key, chunk);
        }

        return chunk.setBlock(x, y, z, block.getType(), block.getData(), notify);
    }

    /**
     * Read a block with read-your-writes semantics: inside the window a
     * position with a pending block returns the pending block, everything
     * else is read from the world.
     */
    public BaseBlock getBlock(World parent, IWorld bukkitWorld, Vector location) {
        if (isWindowOpenOnThisThread() && bukkitWorld != null && location != null) {
            WorldBatch batch = m_batches.get(bukkitWorld.getName());
            if (batch != null) {
                final int x = location.getBlockX();
                final int z = location.getBlockZ();
                PendingChunk chunk = batch.chunks.get(SectionMath.chunkKey(
                        SectionMath.blockToChunk(x), SectionMath.blockToChunk(z)));
                if (chunk != null) {
                    int slot = chunk.getPendingSlot(x, location.getBlockY(), z);
                    if (slot != SectionMath.EMPTY_SLOT) {
                        return new BaseBlock(SectionMath.slotId(slot),
                                SectionMath.slotData(slot));
                    }
                }
            }
        }

        return parent.getBlock(location);
    }

    /**
     * Flush all pending blocks. Called at the end of every block placer
     * run and before demanding entries so those never see stale chunk
     * data. Main thread only.
     */
    public void flush() {
        if (!isWindowOpenOnThisThread() || m_batches.isEmpty()) {
            return;
        }

        for (WorldBatch batch : m_batches.values()) {
            for (PendingChunk chunk : batch.chunks.values()) {
                NmsChunkWriter nmsWriter = m_nmsWriter;

                if (nmsWriter != null && !m_runtimeDisabled
                        && chunk.getCount() >= m_minBlocksPerChunk) {
                    try {
                        List<NmsChunkWriter.OverflowBlock> overflow
                                = nmsWriter.apply(batch.bukkit, chunk);
                        for (NmsChunkWriter.OverflowBlock block : overflow) {
                            classicPlace(batch.parent, block.x, block.y, block.z,
                                    block.id, block.data, block.notify);
                        }
                        continue;
                    } catch (Throwable ex) {
                        m_runtimeDisabled = true;
                        log("Direct chunk placement failed (" + ex
                                + "), switching to classic block placement permanently.");
                    }
                }

                classicPlaceChunk(batch.parent, chunk);
            }
        }

        m_batches.clear();
    }

    /**
     * Replay a whole pending chunk through the classic per block path
     */
    private void classicPlaceChunk(World parent, PendingChunk chunk) {
        final int bx = chunk.getX() << 4;
        final int bz = chunk.getZ() << 4;

        for (int s = 0; s < SectionMath.SECTIONS_PER_CHUNK; s++) {
            PendingSection section = chunk.getSection(s);
            if (section == null) {
                continue;
            }

            final int sy = s << 4;
            for (int index = 0; index < SectionMath.SECTION_SIZE; index++) {
                int slot = section.getSlot(index);
                if (slot == SectionMath.EMPTY_SLOT) {
                    continue;
                }

                classicPlace(parent,
                        bx + SectionMath.indexToX(index),
                        sy + SectionMath.indexToY(index),
                        bz + SectionMath.indexToZ(index),
                        SectionMath.slotId(slot), SectionMath.slotData(slot),
                        SectionMath.slotNotify(slot));
            }
        }
    }

    private void classicPlace(World parent, int x, int y, int z,
            int id, int data, boolean notify) {
        try {
            parent.setBlock(new Vector(x, y, z), new BaseBlock(id, data), notify);
        } catch (WorldEditException ex) {
            if (!m_replayErrorLogged) {
                m_replayErrorLogged = true;
                log("Error while replaying batched block: " + ex);
            }
        }
    }

    private static org.bukkit.World resolveBukkitWorld(IWorld world) {
        if (world instanceof IBukkitWorld) {
            return ((IBukkitWorld) world).getWorld();
        }
        return null;
    }
}
