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
import com.sk89q.worldedit.blocks.BlockType;
import com.sk89q.worldedit.world.World;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
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
     * Time allowance callback for a budgeted flush: chunks are flushed one
     * at a time and the flush stops (carrying the rest over to the next
     * placer run) once the allowance is used up. At least one chunk is
     * always flushed so carried batches can never starve.
     */
    public interface IFlushLimit {

        /**
         * @return true while there is time allowance left
         */
        boolean shouldContinue();
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
     * The thread that owns batches carried over from a budget limited
     * flush (the main thread), null when nothing is carried over. Keeps
     * the read overlay and clearPending alive between placer runs.
     */
    private volatile Thread m_carryOverThread;

    /**
     * Pending blocks per world name, only touched inside the window
     */
    private final Map<String, WorldBatch> m_batches = new HashMap<String, WorldBatch>();

    /**
     * Classic replay error was logged already
     */
    private boolean m_replayErrorLogged;

    /**
     * Chunk flush error was logged already
     */
    private boolean m_flushErrorLogged;

    /**
     * Upper bound of allocated pending section buffers (memory cap). A
     * section buffer costs 32 KB (slot + sequence array), so the default
     * bounds the batch memory at 32 MB even for degenerate scattered
     * edits. Blocks that would need a new section beyond the cap fall
     * back to the classic path, which naturally throttles admission
     * against the tick budget (FAWE bounds its queue the same way with
     * TARGET_SIZE / memory checks).
     */
    private static final int DEFAULT_MAX_PENDING_SECTIONS = 1024;

    /**
     * The active pending section cap (test seam can lower it)
     */
    private int m_maxPendingSections = DEFAULT_MAX_PENDING_SECTIONS;

    /**
     * Number of currently allocated pending section buffers over all
     * worlds and chunks (including carried over batches)
     */
    private int m_pendingSections;

    /**
     * Batch global write sequence: makes the classic replay order
     * comparable across chunks so attachments (queued last by WorldEdit)
     * can be deferred behind the supports of ALL chunks of a flush, not
     * just their own. Reset when no batches are alive.
     */
    private int m_writeSeq;

    /**
     * A classic replay block whose placement is deferred to the end of
     * the flush pass (attachments must come after the supports of every
     * chunk flushed in this pass)
     */
    private static final class DeferredBlock implements Comparable<DeferredBlock> {

        final World parent;
        final int seq;
        final int x;
        final int y;
        final int z;
        final int id;
        final int data;
        final boolean notify;

        DeferredBlock(World parent, int seq, int x, int y, int z,
                int id, int data, boolean notify) {
            this.parent = parent;
            this.seq = seq;
            this.x = x;
            this.y = y;
            this.z = z;
            this.id = id;
            this.data = data;
            this.notify = notify;
        }

        @Override
        public int compareTo(DeferredBlock other) {
            return this.seq < other.seq ? -1 : (this.seq == other.seq ? 0 : 1);
        }
    }

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

            String sanityError = sanityCheckLoadedChunk(worlds.get(0), handles);
            if (sanityError != null) {
                log("Direct chunk placement not available: " + sanityError
                        + " - falling back to classic block placement.");
                return;
            }

            m_nmsWriter = new NmsChunkWriter(handles);
            final String layoutName;
            if (handles.layout == NmsHandles.Layout.ID16) {
                layoutName = handles.meta16Field != null
                        ? "NotEnoughIDs 16 bit id + 16 bit meta"
                        : "NotEnoughIDs 16 bit id + nibble meta";
            } else {
                layoutName = "vanilla 12 bit";
            }
            log(String.format(
                    "Direct chunk placement active (%s section layout, min %d blocks per chunk).",
                    layoutName, m_minBlocksPerChunk));
        } catch (ProbeException ex) {
            log("Direct chunk placement not available: " + ex.getMessage()
                    + " - falling back to classic block placement.");
        } catch (Throwable ex) {
            log("Direct chunk placement not available: " + ex
                    + " - falling back to classic block placement.");
        }
    }

    /**
     * Best effort sanity check of the detected section layout against a
     * loaded chunk: the id array of a live section must have the expected
     * 4096 entries. When no loaded chunk with a live section is found the
     * check is repeated by the NMS writer on the first flush.
     *
     * @return an error description when the layout check failed, null
     * when it passed or could not run
     */
    private static String sanityCheckLoadedChunk(org.bukkit.World world, NmsHandles handles) {
        try {
            org.bukkit.Chunk[] loaded = world.getLoadedChunks();
            if (loaded == null || loaded.length == 0) {
                return null;
            }

            Object handle = handles.worldGetHandle.invoke(world);
            Object chunk = handles.getChunk.invoke(handle,
                    loaded[0].getX(), loaded[0].getZ());
            Object[] sections = (Object[]) handles.getSections.invoke(chunk);
            for (Object section : sections) {
                if (section == null) {
                    continue;
                }
                return NmsChunkWriter.checkSectionArrays(section, handles);
            }
        } catch (Throwable ex) {
            //Could not run the check here - the writer verifies the first
            //live section it touches and disables cleanly on mismatch
        }
        return null;
    }

    /**
     * Open the batching window. Must be called from the main thread by
     * the block placer before it starts draining the queues. Also opens
     * when batches were carried over from the previous run so those can
     * be flushed even after a runtime disable.
     */
    public void openWindow() {
        if (m_enabled && !m_probeAttempted) {
            probe();
        }

        if (!hasCarryOver()) {
            //No live pending blocks - safe point to rewind the global
            //write sequence so it can never overflow
            m_writeSeq = 0;
        }

        if (isActive() || hasCarryOver()) {
            m_windowThread = Thread.currentThread();
            m_carryOverThread = null;
        }
    }

    /**
     * Flush everything and close the batching window
     */
    public void closeWindow() {
        closeWindow(null);
    }

    /**
     * Flush and close the batching window. When a flush limit is given the
     * flush stops once the time allowance is used up (at least one chunk
     * is always flushed) and the remaining chunks carry over to the next
     * placer run; the read overlay stays alive for them.
     *
     * @param limit the flush time allowance, null for a full flush
     */
    public void closeWindow(IFlushLimit limit) {
        if (!isWindowOpenOnThisThread()) {
            return;
        }
        try {
            flushInternal(limit);
        } finally {
            m_windowThread = null;
            m_carryOverThread = m_batches.isEmpty() ? null : Thread.currentThread();
        }
    }

    /**
     * True when a budget limited flush left chunks behind for the next
     * placer run
     */
    public boolean hasCarryOver() {
        if (m_batches.isEmpty()) {
            return false;
        }
        for (WorldBatch batch : m_batches.values()) {
            if (!batch.chunks.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Fully flush all pending blocks, even when no window is open (claims
     * the window temporarily). Used on shutdown and reload so carried
     * over batches are never lost. Must be called from the thread that
     * owns the batches (the server main thread).
     */
    public void forceFlush() {
        if (isWindowOpenOnThisThread()) {
            flush();
            return;
        }

        if (m_windowThread != null || m_batches.isEmpty()) {
            //Another thread owns the window or there is nothing to do
            return;
        }

        m_windowThread = Thread.currentThread();
        try {
            flushInternal(null);
        } finally {
            m_windowThread = null;
            m_carryOverThread = m_batches.isEmpty() ? null : Thread.currentThread();
        }
    }

    private boolean isWindowOpenOnThisThread() {
        return m_windowThread == Thread.currentThread();
    }

    /**
     * True when this thread may see (and must maintain) the pending block
     * overlay: the window is open on this thread, or this thread owns
     * batches carried over between placer runs.
     */
    private boolean isOverlayVisibleToThisThread() {
        final Thread current = Thread.currentThread();
        return m_windowThread == current
                || (m_windowThread == null && m_carryOverThread == current);
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

        //Memory cap: a block that needs a NEW section buffer beyond the
        //cap is refused (classic path); writes into already allocated
        //sections stay free, so memory is strictly bounded while dense
        //edits keep batching
        final boolean needsNewSection
                = chunk == null || chunk.needsNewSection(y);
        if (needsNewSection && m_pendingSections >= m_maxPendingSections) {
            return false;
        }

        if (chunk == null) {
            chunk = new PendingChunk(
                    SectionMath.blockToChunk(x), SectionMath.blockToChunk(z));
            batch.chunks.put(key, chunk);
        }

        if (!chunk.setBlock(x, y, z, block.getType(), block.getData(), notify,
                m_writeSeq++)) {
            return false;
        }

        if (needsNewSection) {
            m_pendingSections++;
        }
        return true;
    }

    /**
     * Remove the pending block at a position. Must be called before every
     * classic path world write inside the window (or while batches are
     * carried over): the classic write would otherwise be overwritten by
     * the stale pending value on flush, and the tile entity cleanup would
     * delete its tile.
     */
    public void clearPending(IWorld bukkitWorld, Vector location) {
        if (bukkitWorld == null || location == null) {
            return;
        }

        //Buffer-first engine: a classic path write here must also drop any
        //block the buffered engine holds at this position, so the stale
        //buffered value is not flushed over the newer classic write.
        JobBufferRegistry.getInstance().clearBuffered(bukkitWorld,
                location.getBlockX(), location.getBlockY(), location.getBlockZ());

        if (!isOverlayVisibleToThisThread()) {
            return;
        }

        WorldBatch batch = m_batches.get(bukkitWorld.getName());
        if (batch == null) {
            return;
        }

        final int x = location.getBlockX();
        final int z = location.getBlockZ();
        final long key = SectionMath.chunkKey(
                SectionMath.blockToChunk(x), SectionMath.blockToChunk(z));

        PendingChunk chunk = batch.chunks.get(key);
        if (chunk == null) {
            return;
        }

        chunk.clear(x, location.getBlockY(), z);
        if (chunk.getCount() == 0) {
            batch.chunks.remove(key);
            m_pendingSections -= chunk.getSectionCount();
            if (batch.chunks.isEmpty()) {
                m_batches.remove(bukkitWorld.getName());
            }
        }
    }

    /**
     * The pending slot for a position, {@link SectionMath#EMPTY_SLOT} when
     * the overlay is not visible to this thread or nothing is pending
     */
    private int pendingSlot(IWorld bukkitWorld, Vector location) {
        if (!isOverlayVisibleToThisThread() || bukkitWorld == null || location == null) {
            return SectionMath.EMPTY_SLOT;
        }

        WorldBatch batch = m_batches.get(bukkitWorld.getName());
        if (batch == null) {
            return SectionMath.EMPTY_SLOT;
        }

        final int x = location.getBlockX();
        final int z = location.getBlockZ();
        PendingChunk chunk = batch.chunks.get(SectionMath.chunkKey(
                SectionMath.blockToChunk(x), SectionMath.blockToChunk(z)));
        if (chunk == null) {
            return SectionMath.EMPTY_SLOT;
        }

        return chunk.getPendingSlot(x, location.getBlockY(), z);
    }

    /**
     * The pending slot for a position, consulting first this window's own
     * overlay (main thread) and then the buffer-first engine overlay (the
     * producer thread's own job buffer). {@link SectionMath#EMPTY_SLOT} when
     * nothing is pending.
     */
    private int resolveSlot(IWorld bukkitWorld, Vector location) {
        int slot = pendingSlot(bukkitWorld, location);
        if (slot != SectionMath.EMPTY_SLOT) {
            return slot;
        }
        return JobBufferRegistry.getInstance().overlayGet(bukkitWorld,
                location.getBlockX(), location.getBlockY(), location.getBlockZ());
    }

    /**
     * Read a block with read-your-writes semantics: a position with a
     * pending block returns the pending block, everything else is read
     * from the world.
     */
    public BaseBlock getBlock(World parent, IWorld bukkitWorld, Vector location) {
        int slot = resolveSlot(bukkitWorld, location);
        if (slot != SectionMath.EMPTY_SLOT) {
            return new BaseBlock(SectionMath.slotId(slot), SectionMath.slotData(slot));
        }

        return parent.getBlock(location);
    }

    /**
     * Read a block id with read-your-writes semantics
     */
    public int getBlockType(World parent, IWorld bukkitWorld, Vector location) {
        int slot = resolveSlot(bukkitWorld, location);
        if (slot != SectionMath.EMPTY_SLOT) {
            return SectionMath.slotId(slot);
        }

        return parent.getBlockType(location);
    }

    /**
     * Read a block metadata value with read-your-writes semantics
     */
    public int getBlockData(World parent, IWorld bukkitWorld, Vector location) {
        int slot = resolveSlot(bukkitWorld, location);
        if (slot != SectionMath.EMPTY_SLOT) {
            return SectionMath.slotData(slot);
        }

        return parent.getBlockData(location);
    }

    /**
     * Read a lazy block with read-your-writes semantics
     */
    public BaseBlock getLazyBlock(World parent, IWorld bukkitWorld, Vector location) {
        int slot = resolveSlot(bukkitWorld, location);
        if (slot != SectionMath.EMPTY_SLOT) {
            return new BaseBlock(SectionMath.slotId(slot), SectionMath.slotData(slot));
        }

        return parent.getLazyBlock(location);
    }

    /**
     * Flush all pending blocks. Called before demanding entries (those
     * must see the world with all batched blocks applied), on shutdown
     * and on reload. Main thread only.
     */
    public void flush() {
        flushInternal(null);
    }

    /**
     * Flush pending chunks one at a time. Every chunk is removed from the
     * batch before it is written, and a failing chunk never stops the
     * remaining ones (per chunk catch). With a flush limit the loop stops
     * once the allowance is used up - at least one chunk always gets
     * flushed - and the rest stays in {@link #m_batches} to carry over.
     */
    private void flushInternal(IFlushLimit limit) {
        if (!isWindowOpenOnThisThread() || m_batches.isEmpty()) {
            return;
        }

        //Classic replay blocks WorldEdit queued last (attachments like
        //torches, levers, doors) are deferred to the end of this flush
        //pass so the supports of ALL chunks flushed in this pass exist
        //before any attachment fires its placement physics
        final List<DeferredBlock> deferred = new ArrayList<DeferredBlock>();

        try {
            boolean first = true;
            final Iterator<WorldBatch> worldIterator = m_batches.values().iterator();
            while (worldIterator.hasNext()) {
                final WorldBatch batch = worldIterator.next();

                final Iterator<PendingChunk> chunkIterator = batch.chunks.values().iterator();
                while (chunkIterator.hasNext()) {
                    if (!first && limit != null && !limit.shouldContinue()) {
                        //Budget used up - the remaining chunks carry over
                        return;
                    }

                    final PendingChunk chunk = chunkIterator.next();
                    //Remove before processing: a failing chunk must never
                    //survive to the next window (stale overlay, double apply)
                    chunkIterator.remove();
                    m_pendingSections -= chunk.getSectionCount();
                    first = false;

                    try {
                        flushChunk(batch, chunk, deferred);
                    } catch (Throwable ex) {
                        if (!m_flushErrorLogged) {
                            m_flushErrorLogged = true;
                            log(String.format(
                                    "Error while flushing batched chunk %d,%d: %s",
                                    chunk.getX(), chunk.getZ(), ex));
                        }
                    }
                }

                if (batch.chunks.isEmpty()) {
                    worldIterator.remove();
                }
            }
        } finally {
            //Deferred attachments are placed even when the budget ran out
            //mid pass - their chunks are already written, they must never
            //be lost
            replayDeferred(deferred);
        }
    }

    /**
     * Replay the deferred attachment blocks in global write order
     */
    private void replayDeferred(List<DeferredBlock> deferred) {
        if (deferred.isEmpty()) {
            return;
        }

        java.util.Collections.sort(deferred);
        for (DeferredBlock block : deferred) {
            try {
                classicPlace(block.parent, block.x, block.y, block.z,
                        block.id, block.data, block.notify);
            } catch (Throwable ex) {
                if (!m_flushErrorLogged) {
                    m_flushErrorLogged = true;
                    log("Error while replaying deferred batched block: " + ex);
                }
            }
        }
    }

    /**
     * Flush a single externally owned pending chunk (a buffer-first engine
     * job buffer) reusing the batch writer's direct NMS write, sub threshold
     * classic replay and attachment deferral. Main thread only. Deferred
     * attachments are replayed at the end of this call so a torch always
     * lands after the supports of this chunk.
     *
     * @param parent the WorldEdit world for the classic replay
     * @param bukkitWorld the AWE world for the direct chunk writes
     * @param chunk the detached pending chunk
     */
    public void flushJobChunk(World parent, IWorld bukkitWorld, PendingChunk chunk) {
        if (parent == null || bukkitWorld == null || chunk == null) {
            return;
        }

        final List<DeferredBlock> deferred = new ArrayList<DeferredBlock>();
        try {
            final org.bukkit.World bukkit = resolveBukkitWorld(bukkitWorld);
            if (bukkit == null) {
                //No Bukkit world: classic replay the whole chunk
                classicPlaceChunk(parent, chunk, deferred);
            } else {
                flushChunk(new WorldBatch(parent, bukkit), chunk, deferred);
            }
        } catch (Throwable ex) {
            if (!m_flushErrorLogged) {
                m_flushErrorLogged = true;
                log("Error while flushing buffered chunk " + chunk.getX()
                        + "," + chunk.getZ() + ": " + ex);
            }
        } finally {
            replayDeferred(deferred);
        }
    }

    /**
     * Flush a single pending chunk: direct NMS section write when the
     * chunk qualifies, classic per block replay otherwise
     */
    private void flushChunk(WorldBatch batch, PendingChunk chunk,
            List<DeferredBlock> deferred) {
        final NmsChunkWriter nmsWriter = m_nmsWriter;

        if (nmsWriter != null && !m_runtimeDisabled
                && chunk.getCount() >= m_minBlocksPerChunk) {
            try {
                List<NmsChunkWriter.OverflowBlock> overflow
                        = nmsWriter.apply(batch.bukkit, chunk);
                for (NmsChunkWriter.OverflowBlock block : overflow) {
                    if (BlockType.shouldPlaceLast(block.id)
                            || BlockType.shouldPlaceFinal(block.id)) {
                        //No per block sequence on the overflow path -
                        //attachments go last (the sort is stable)
                        deferred.add(new DeferredBlock(batch.parent, Integer.MAX_VALUE,
                                block.x, block.y, block.z,
                                block.id, block.data, block.notify));
                    } else {
                        classicPlace(batch.parent, block.x, block.y, block.z,
                                block.id, block.data, block.notify);
                    }
                }
                return;
            } catch (Throwable ex) {
                m_runtimeDisabled = true;
                log("Direct chunk placement failed (" + ex
                        + "), switching to classic block placement permanently.");
            }
        }

        classicPlaceChunk(batch.parent, chunk, deferred);
    }

    /**
     * Replay a whole pending chunk through the classic per block path.
     * The replay places the final value of every position, ordered by the
     * sequence of each position's last write: WorldEdit's reorder stage
     * queues attachments (torches, levers, rails) after their supports,
     * so a coordinate order replay would fire physics on an unsupported
     * attachment and pop it off. Attachment blocks themselves are handed
     * to the deferred list so they run after every chunk of the pass.
     */
    private void classicPlaceChunk(final World parent, PendingChunk chunk,
            final List<DeferredBlock> deferred) {
        chunk.forEachLastWriteOrder(new PendingChunk.IPendingBlockVisitor() {
            @Override
            public void visit(int x, int y, int z, int id, int data,
                    boolean notify, int seq) {
                if (BlockType.shouldPlaceLast(id) || BlockType.shouldPlaceFinal(id)) {
                    deferred.add(new DeferredBlock(parent, seq,
                            x, y, z, id, data, notify));
                } else {
                    classicPlace(parent, x, y, z, id, data, notify);
                }
            }
        });
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

    /**
     * Test seam: enable the writer with a given minimum batch size and
     * NMS writer without running the Bukkit probe. Package private, only
     * for unit tests on fresh (non singleton) instances.
     */
    void configureForTest(int minBlocksPerChunk, NmsChunkWriter nmsWriter) {
        m_enabled = true;
        m_probeAttempted = true;
        m_runtimeDisabled = false;
        m_minBlocksPerChunk = Math.max(1, minBlocksPerChunk);
        m_nmsWriter = nmsWriter;
    }

    /**
     * Test seam: lower the pending section memory cap
     */
    void setMaxPendingSectionsForTest(int maxPendingSections) {
        m_maxPendingSections = Math.max(1, maxPendingSections);
    }

    /**
     * Number of allocated pending section buffers (memory bookkeeping,
     * package private for tests)
     */
    int getPendingSectionCount() {
        return m_pendingSections;
    }
}
