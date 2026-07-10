/*
 * AsyncWorldEdit a performance improvement plugin for Minecraft WorldEdit plugin.
 * Copyright (c) 2016, SBPrime <https://github.com/SBPrime/>
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
package org.primesoft.asyncworldedit.worldedit.extent;

import com.sk89q.worldedit.Vector;
import com.sk89q.worldedit.Vector2D;
import com.sk89q.worldedit.WorldEditException;
import com.sk89q.worldedit.blocks.BaseBlock;
import com.sk89q.worldedit.entity.BaseEntity;
import com.sk89q.worldedit.entity.Entity;
import com.sk89q.worldedit.extent.ChangeSetExtent;
import com.sk89q.worldedit.extent.Extent;
import com.sk89q.worldedit.history.change.BlockChange;
import com.sk89q.worldedit.history.change.EntityCreate;
import com.sk89q.worldedit.util.Location;
import com.sk89q.worldedit.world.biome.BaseBiome;
import java.io.File;
import java.io.IOException;
import java.util.UUID;
import javax.annotation.Nullable;
import static org.primesoft.asyncworldedit.LoggerProvider.log;
import org.primesoft.asyncworldedit.api.playerManager.IPlayerEntry;
import org.primesoft.asyncworldedit.api.worldedit.ICancelabeEditSession;
import org.primesoft.asyncworldedit.chunkbatch.BatchEligibility;
import org.primesoft.asyncworldedit.chunkbatch.undo.ColumnarUndoLog;
import org.primesoft.asyncworldedit.chunkbatch.undo.ColumnarUndoRegistry;
import org.primesoft.asyncworldedit.configuration.ConfigEngine;
import org.primesoft.asyncworldedit.configuration.ConfigProvider;
import org.primesoft.asyncworldedit.worldedit.IAsyncWrapper;
import org.primesoft.asyncworldedit.worldedit.history.change.BiomeChange;
import org.primesoft.asyncworldedit.worldedit.history.changeset.ColumnarUndoSink;
import org.primesoft.asyncworldedit.worldedit.history.changeset.CompositeChangeSet;
import org.primesoft.asyncworldedit.worldedit.history.changeset.IExtendedChangeSet;

/**
 * This class is based on WorldEdit ChangeSetExtent
 *
 * @author SBPrime
 */
public class ExtendedChangeSetExtent extends ChangeSetExtent {

    private final IExtendedChangeSet m_changeSet;
    private final ICancelabeEditSession m_cancelableEditSession;

    /**
     * The session's composite change set; non null only in columnar undo
     * mode (buffered engine requested + undo enabled), which arms the
     * suppression seam in {@link #setBlock}
     */
    private final CompositeChangeSet m_composite;

    /**
     * Columnar log creation failed once (logged); further jobs of this
     * session record through the object change set
     */
    private boolean m_registerErrorLogged;

    public ExtendedChangeSetExtent(ICancelabeEditSession editSession, Extent extent, IExtendedChangeSet changeSet) {
        this(editSession, extent, changeSet, null);
    }

    public ExtendedChangeSetExtent(ICancelabeEditSession editSession, Extent extent,
            IExtendedChangeSet changeSet, CompositeChangeSet composite) {
        super(extent, new ProxyChangeSet(changeSet, editSession));

        m_changeSet = changeSet;
        m_cancelableEditSession = editSession;
        m_composite = composite;
    }

    @Override
    public boolean setBiome(Vector2D position, BaseBiome biome) {
        BaseBiome previous = getBiome(position);

        try {
            m_changeSet.addExtended(new BiomeChange(position, previous, biome), m_cancelableEditSession);
        } catch (WorldEditException ex) {
            return false;
        }

        return super.setBiome(position, biome);
    }

    @Override
    public boolean setBlock(Vector location, BaseBlock block) throws WorldEditException {
        //Columnar undo: a buffer-eligible block of a real async job skips
        //the dispatched old-block read and the per block object recording
        //entirely - its old value is captured at flush time into the job's
        //columnar log, registered here on first suppression. INVARIANT:
        ///undo and //redo replays write through bypassHistory, never reach
        //this extent, never register a log, so the flush-time capture
        //ignores their writes and a replay can never corrupt its own
        //history.
        if (suppressToColumnar(location, block)) {
            return super.setBlock(location, block);
        }

        BaseBlock previous = getBlock(location);
        m_changeSet.addExtended(new BlockChange(location.toBlockVector(), previous, block), m_cancelableEditSession);
        return super.setBlock(location, block);
    }

    /**
     * True when this write's undo is captured columnar at flush time
     * instead of being recorded here: the buffered engine and the columnar
     * mode are active, the block matches the exact buffer eligibility
     * predicate of AsyncWorld.bufferBlock and the write belongs to a real
     * async job (extracted from the IAsyncWrapper the session wrapped
     * around the location/block). Everything else - loose writes,
     * synchronous writes, tiles/NBT, wildcard data - records exactly as
     * before.
     */
    private boolean suppressToColumnar(Vector location, BaseBlock block) {
        if (m_composite == null || !ConfigProvider.isBufferedEngine()) {
            return false;
        }
        final ConfigEngine engine = ConfigProvider.engine();
        if (engine == null || !engine.isColumnarUndo()) {
            return false;
        }

        final IAsyncWrapper wrapper;
        if (location instanceof IAsyncWrapper) {
            wrapper = (IAsyncWrapper) location;
        } else if (block instanceof IAsyncWrapper) {
            wrapper = (IAsyncWrapper) block;
        } else {
            //No wrapper (e.g. a plugin writing to the extent directly):
            //record as today
            return false;
        }

        if (!wrapper.isAsync() || wrapper.getJobId() < 0) {
            //Synchronous or loose writes may take the classic world path
            //that the flush never captures: record as today
            return false;
        }

        if (!BatchEligibility.isBatchable(block, location.getBlockY())) {
            //Tiles/NBT/wildcards keep the full fidelity object record
            return false;
        }

        final IPlayerEntry player = wrapper.getPlayer();
        final UUID uuid = player == null ? null : player.getUUID();
        final int jobId = wrapper.getJobId();

        //Owner-bound resolution: only a sink registered by THIS session's
        //composite is trusted. Job ids are reused (max(live)+1), so a hit
        //left behind by a dead job of an earlier session is evicted and
        //sealed by resolveOwned - without the check the new edit would
        //capture into the old session's log and lose its own undo.
        if (ColumnarUndoRegistry.resolveOwned(uuid, jobId, m_composite) != null) {
            return true;
        }
        return registerJobLog(uuid, jobId);
    }

    /**
     * First suppression of a (player, job): create the job's columnar undo
     * log (spooling to the player's undo folder above the configured
     * threshold), register its capture sink for the flush and attach it to
     * the session's composite change set. A creation failure logs once and
     * falls back to the object recording.
     */
    private boolean registerJobLog(UUID uuid, int jobId) {
        try {
            final ConfigEngine engine = ConfigProvider.engine();
            final long thresholdBytes = 1024L * 1024L * (engine != null
                    ? engine.getUndoSpoolThresholdMb()
                    : ConfigEngine.DEFAULT_UNDO_SPOOL_THRESHOLD_MB);

            final File folder = new File(ConfigProvider.getUndoFolder(),
                    uuid != null ? uuid.toString() : "console");
            if (!folder.exists() && !folder.mkdirs()) {
                throw new IOException("unable to create " + folder.getPath());
            }
            final File spool = new File(folder, String.format("columnar.%1$d.%2$d.bin",
                    jobId, System.currentTimeMillis()));

            final ColumnarUndoSink sink = new ColumnarUndoSink(
                    new ColumnarUndoLog(spool, thresholdBytes), m_changeSet);
            if (!ColumnarUndoRegistry.register(uuid, jobId, sink, m_composite)) {
                //Another thread of the same job won the registration race;
                //nothing was written to this log yet. Only trust the hit
                //when it belongs to this session's composite.
                sink.close();
                return ColumnarUndoRegistry.resolveOwned(uuid, jobId, m_composite) != null;
            }
            m_composite.attach(sink);
            return true;
        } catch (Throwable ex) {
            if (!m_registerErrorLogged) {
                m_registerErrorLogged = true;
                log("Error while creating a columnar undo log, this session"
                        + " records undo through the object change set: " + ex);
            }
            return false;
        }
    }

    @Nullable
    @Override
    public Entity createEntity(Location location, BaseEntity state) {
        Entity entity = super.createEntity(location, state);
        if (state != null) {
            try {
                m_changeSet.addExtended(new EntityCreate(location, state, entity), m_cancelableEditSession);
            } catch (WorldEditException ex) {
                return null;
            }
        }
        return entity;
    }

}
