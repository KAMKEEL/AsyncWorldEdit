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
package org.primesoft.asyncworldedit.chunkbatch.nms;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * The resolved NMS methods and fields needed for direct chunk section
 * writes on a 1.7.10 server. Produced by {@link NmsProbe}, consumed by
 * {@link NmsChunkWriter}. All members are resolved once at probe time;
 * only the TileEntity/ChunkPosition members are resolved lazily by the
 * writer because their classes are only reachable from live map entries.
 *
 * @author KAMKEEL
 */
public final class NmsHandles {

    /**
     * The chunk section storage layout
     */
    public enum Layout {

        /**
         * Vanilla 1.7.10: byte[] LSB ids + optional NibbleArray MSB ids
         * (12 bit) + NibbleArray metadata
         */
        VANILLA,
        /**
         * NotEnoughIDs: short[] ids (16 bit) + NibbleArray metadata
         */
        ID16
    }

    /**
     * CraftWorld.getHandle() -> WorldServer
     */
    public final Method worldGetHandle;

    /**
     * WorldServer.getChunkFromChunkCoords(int, int) / func_72964_e
     */
    public final Method getChunk;

    /**
     * Chunk.getBlockStorageArray() / func_76587_i
     */
    public final Method getSections;

    /**
     * Chunk.generateSkylightMap() / func_76603_b - recalculates the
     * heightmap and the sky light columns
     */
    public final Method generateSkylightMap;

    /**
     * Chunk.setChunkModified() / func_76630_e, may be null when
     * {@link #isModifiedField} is used instead
     */
    public final Method setChunkModified;

    /**
     * Chunk.isModified / field_76643_l, only used when
     * {@link #setChunkModified} is null
     */
    public final Field isModifiedField;

    /**
     * Chunk.chunkTileEntityMap / field_150816_i
     */
    public final Field tileEntityMapField;

    /**
     * World.provider / field_73011_w
     */
    public final Field providerField;

    /**
     * WorldProvider.hasNoSky / field_76576_e
     */
    public final Field hasNoSkyField;

    /**
     * World.func_147451_t(int, int, int) - full relight of one position,
     * optional (null when not found)
     */
    public final Method relight;

    /**
     * Block.getBlockById(int) / func_149729_e, optional
     */
    public final Method blockGetById;

    /**
     * Block.getLightValue() / func_149750_m, optional
     */
    public final Method blockGetLightValue;

    /**
     * The detected section layout
     */
    public final Layout layout;

    /**
     * ExtendedBlockStorage(int, boolean) constructor
     */
    public final Constructor<?> sectionCtor;

    /**
     * ExtendedBlockStorage.removeInvalidBlocks() / func_76672_e -
     * recalculates the non air and random tick block counters
     */
    public final Method removeInvalidBlocks;

    /**
     * The 16 bit id array field (ID16 layout only, null otherwise)
     */
    public final Field ids16Field;

    /**
     * The LSB id byte array field (VANILLA layout only, null otherwise)
     */
    public final Field lsbField;

    /**
     * The MSB id NibbleArray field (VANILLA layout only, null otherwise)
     */
    public final Field msbField;

    /**
     * The metadata NibbleArray field. May be null when {@link #meta16Field}
     * is set (the vanilla nibble metadata is dead on GTNH NEID servers).
     */
    public final Field metaField;

    /**
     * The GTNH NotEnoughIds 16 bit metadata short[] field
     * (block16BMetaArray). When not null it is the ONLY authoritative
     * metadata storage; the vanilla NibbleArray must not be written.
     */
    public final Field meta16Field;

    /**
     * NibbleArray(int, int) constructor, null when {@link #meta16Field}
     * is set (no nibble machinery needed)
     */
    public final Constructor<?> nibbleCtor;

    /**
     * NibbleArray.data / field_76585_a, null when {@link #meta16Field}
     * is set
     */
    public final Field nibbleDataField;

    public NmsHandles(Method worldGetHandle, Method getChunk, Method getSections,
            Method generateSkylightMap, Method setChunkModified, Field isModifiedField,
            Field tileEntityMapField, Field providerField, Field hasNoSkyField,
            Method relight, Method blockGetById, Method blockGetLightValue,
            Layout layout, Constructor<?> sectionCtor, Method removeInvalidBlocks,
            Field ids16Field, Field lsbField, Field msbField, Field metaField,
            Field meta16Field,
            Constructor<?> nibbleCtor, Field nibbleDataField) {
        this.worldGetHandle = worldGetHandle;
        this.getChunk = getChunk;
        this.getSections = getSections;
        this.generateSkylightMap = generateSkylightMap;
        this.setChunkModified = setChunkModified;
        this.isModifiedField = isModifiedField;
        this.tileEntityMapField = tileEntityMapField;
        this.providerField = providerField;
        this.hasNoSkyField = hasNoSkyField;
        this.relight = relight;
        this.blockGetById = blockGetById;
        this.blockGetLightValue = blockGetLightValue;
        this.layout = layout;
        this.sectionCtor = sectionCtor;
        this.removeInvalidBlocks = removeInvalidBlocks;
        this.ids16Field = ids16Field;
        this.lsbField = lsbField;
        this.msbField = msbField;
        this.metaField = metaField;
        this.meta16Field = meta16Field;
        this.nibbleCtor = nibbleCtor;
        this.nibbleDataField = nibbleDataField;
    }
}
