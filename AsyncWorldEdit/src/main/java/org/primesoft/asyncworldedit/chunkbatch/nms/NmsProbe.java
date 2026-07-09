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
 * One time structural capability probe for direct chunk section writes on
 * a 1.7.10 server (Cauldron/CraftBukkit hybrid).
 *
 * The probe never invokes any server code: it only inspects the class
 * structure starting from the Bukkit world implementation class
 * (CraftWorld). Members are looked up by candidate name lists covering the
 * three namings that can appear at runtime:
 * SRG (production Cauldron/Forge, e.g. func_76587_i), MCP (development,
 * e.g. getBlockStorageArray) and CraftBukkit (e.g. getSections).
 *
 * The section id storage layout is detected from the declared fields of
 * the ExtendedBlockStorage class: a short[] field means the NotEnoughIDs
 * 16 bit patch is installed (block16BArray), otherwise the vanilla
 * byte[] LSB + NibbleArray MSB layout is used. Supporting both layouts
 * explicitly avoids the id truncation bugs that come from writing 12 bit
 * arrays on a NEID server.
 *
 * @author KAMKEEL
 */
public final class NmsProbe {

    private static final String[] GET_CHUNK = {
        "func_72964_e", "getChunkFromChunkCoords", "getChunkAt"};

    private static final String[] GET_SECTIONS = {
        "func_76587_i", "getBlockStorageArray", "getSections", "i"};

    private static final String[] GENERATE_SKYLIGHT_MAP = {
        "func_76603_b", "generateSkylightMap", "initLighting"};

    private static final String[] SET_CHUNK_MODIFIED = {
        "func_76630_e", "setChunkModified", "e"};

    private static final String[] IS_MODIFIED = {
        "field_76643_l", "isModified", "n"};

    private static final String[] TILE_ENTITY_MAP = {
        "field_150816_i", "chunkTileEntityMap", "tileEntities"};

    private static final String[] PROVIDER = {
        "field_73011_w", "provider", "worldProvider"};

    private static final String[] HAS_NO_SKY = {
        "field_76576_e", "hasNoSky", "g"};

    private static final String[] RELIGHT = {
        "func_147451_t", "updateAllLightTypes"};

    private static final String[] REMOVE_INVALID_BLOCKS = {
        "func_76672_e", "removeInvalidBlocks", "recalcBlockCounts"};

    private static final String[] META_ARRAY = {
        "field_76678_f", "blockMetadataArray", "blockData"};

    private static final String[] MSB_ARRAY = {
        "field_76681_e", "blockMSBArray", "extBlockIds"};

    /**
     * The NotEnoughIDs 16 bit id array. The field is injected by the NEID
     * patch and keeps its name in SRG and MCP environments.
     */
    private static final String[] ID16_ARRAY = {
        "block16BArray"};

    /**
     * The vanilla LSB id array: SRG, MCP and CraftBukkit names
     */
    private static final String[] LSB_ARRAY = {
        "field_76679_d", "blockLSBArray", "blockIds"};

    /**
     * The GTNH NotEnoughIds 16 bit metadata array (2.x mixin field). When
     * present the vanilla metadata NibbleArray is dead and this array is
     * the only authoritative metadata storage.
     */
    private static final String[] META16_ARRAY = {
        "block16BMetaArray"};

    private static final String[] BLOCK_CLASS = {
        "net.minecraft.block.Block", "net.minecraft.server.v1_7_R4.Block"};

    private static final String[] BLOCK_GET_BY_ID = {
        "func_149729_e", "getBlockById", "getById"};

    private static final String[] BLOCK_GET_LIGHT_VALUE = {
        "func_149750_m", "getLightValue"};

    private NmsProbe() {
    }

    /**
     * Probe the server for direct chunk write capability.
     *
     * @param bukkitWorldClass the class of the Bukkit world implementation
     * (CraftWorld)
     * @return the resolved handles
     * @throws ProbeException when a required member is missing; the message
     * states what is missing
     */
    public static NmsHandles probe(Class<?> bukkitWorldClass) throws ProbeException {
        if (bukkitWorldClass == null) {
            throw new ProbeException("no Bukkit world available");
        }

        final Method getHandle;
        try {
            getHandle = bukkitWorldClass.getMethod("getHandle");
        } catch (NoSuchMethodException ex) {
            throw new ProbeException("no getHandle() on " + bukkitWorldClass.getName(), ex);
        }
        Class<?> worldClass = getHandle.getReturnType();
        if (worldClass == Object.class || worldClass.isPrimitive()) {
            throw new ProbeException("getHandle() does not return an NMS world (found "
                    + worldClass.getName() + ")");
        }

        Method getChunk = findMethod(worldClass, GET_CHUNK, "NMS world getChunkFromChunkCoords",
                int.class, int.class);
        Class<?> chunkClass = getChunk.getReturnType();
        if (chunkClass == Object.class || chunkClass.isPrimitive()) {
            throw new ProbeException("getChunkFromChunkCoords does not return an NMS chunk");
        }

        Method getSections = findMethod(chunkClass, GET_SECTIONS, "chunk getBlockStorageArray");
        Class<?> sectionsType = getSections.getReturnType();
        if (!sectionsType.isArray()) {
            throw new ProbeException("getBlockStorageArray does not return an array");
        }
        Class<?> sectionClass = sectionsType.getComponentType();

        Method generateSkylightMap = findMethod(chunkClass, GENERATE_SKYLIGHT_MAP,
                "chunk generateSkylightMap");

        Method setChunkModified = null;
        Field isModifiedField = null;
        try {
            setChunkModified = findMethod(chunkClass, SET_CHUNK_MODIFIED, "chunk setChunkModified");
        } catch (ProbeException ex) {
            isModifiedField = findField(chunkClass, IS_MODIFIED, boolean.class,
                    "chunk setChunkModified/isModified");
        }

        Field tileEntityMapField = findField(chunkClass, TILE_ENTITY_MAP, null,
                "chunk tile entity map");
        if (!java.util.Map.class.isAssignableFrom(tileEntityMapField.getType())) {
            throw new ProbeException("chunk tile entity map field is not a Map");
        }

        Field providerField = findField(worldClass, PROVIDER, null, "world provider");
        Field hasNoSkyField = findField(providerField.getType(), HAS_NO_SKY, boolean.class,
                "world provider hasNoSky");

        Method relight = null;
        try {
            relight = findMethod(worldClass, RELIGHT, "world relight",
                    int.class, int.class, int.class);
        } catch (ProbeException ex) {
            //Optional: without it lighting relies on generateSkylightMap only
        }

        Method blockGetById = null;
        Method blockGetLightValue = null;
        for (String className : BLOCK_CLASS) {
            try {
                Class<?> blockClass = Class.forName(className);
                blockGetById = findMethod(blockClass, BLOCK_GET_BY_ID, "Block.getBlockById",
                        int.class);
                blockGetLightValue = findMethod(blockClass, BLOCK_GET_LIGHT_VALUE,
                        "Block.getLightValue");
                if (blockGetLightValue.getReturnType() != int.class) {
                    blockGetById = null;
                    blockGetLightValue = null;
                }
                break;
            } catch (ClassNotFoundException ex) {
                //Optional: try the next name / go without light emitter relight
            } catch (ProbeException ex) {
                //Optional: go without light emitter relight
            }
        }

        //Section layout detection: match the id array by NAME first. A
        //structure-only pick (first short[]/byte[] found) can be fooled by
        //a coremod adding an unrelated array field and would then silently
        //corrupt the world. Structure is only used as a fallback when no
        //name matches AND exactly one candidate of one type exists.
        Field ids16Field = findFieldOrNull(sectionClass, ID16_ARRAY, short[].class);
        Field lsbField = findFieldOrNull(sectionClass, LSB_ARRAY, byte[].class);

        if (ids16Field == null && lsbField == null) {
            int shortArrays = 0;
            int byteArrays = 0;
            Field shortCandidate = null;
            Field byteCandidate = null;
            for (Field field : sectionClass.getDeclaredFields()) {
                if (field.getType() == short[].class) {
                    shortArrays++;
                    shortCandidate = field;
                } else if (field.getType() == byte[].class) {
                    byteArrays++;
                    byteCandidate = field;
                }
            }

            if (shortArrays == 1 && byteArrays == 0) {
                ids16Field = shortCandidate;
            } else if (byteArrays == 1 && shortArrays == 0) {
                lsbField = byteCandidate;
            } else if (shortArrays != 0 || byteArrays != 0) {
                throw new ProbeException(
                        "ambiguous section id storage in " + sectionClass.getName()
                        + " (" + shortArrays + " short[] and " + byteArrays
                        + " byte[] fields, none with a known name)");
            }
        }

        final NmsHandles.Layout layout;
        if (ids16Field != null) {
            layout = NmsHandles.Layout.ID16;
            ids16Field.setAccessible(true);
        } else {
            if (lsbField == null) {
                throw new ProbeException("section id array not found (no short[] and no byte[] in "
                        + sectionClass.getName() + ")");
            }
            layout = NmsHandles.Layout.VANILLA;
            lsbField.setAccessible(true);
        }

        //Metadata storage. GTNH NotEnoughIds (2.x) widens the metadata to a
        //16 bit short[] (block16BMetaArray) and leaves the vanilla
        //NibbleArray in place but DEAD (never read again) - writing the
        //nibble array on such a server silently drops every block's
        //metadata. The original fewizz NEID and vanilla keep the metadata
        //in the NibbleArray.
        Field meta16Field = null;
        if (layout == NmsHandles.Layout.ID16) {
            meta16Field = findFieldOrNull(sectionClass, META16_ARRAY, short[].class);
            if (meta16Field != null) {
                meta16Field.setAccessible(true);
            }
        }

        Field metaField;
        Field nibbleDataField = null;
        Constructor<?> nibbleCtor = null;
        Field msbField = null;

        if (meta16Field != null) {
            //16 bit metadata is authoritative; the nibble machinery is not
            //needed (the vanilla metadata field may even disappear in a
            //future NEID version)
            metaField = findFieldOrNull(sectionClass, META_ARRAY, null);
        } else {
            metaField = findField(sectionClass, META_ARRAY, null, "section metadata array");
            Class<?> nibbleClass = metaField.getType();
            if (nibbleClass.isPrimitive() || nibbleClass.isArray()) {
                throw new ProbeException("section metadata field is not a NibbleArray");
            }

            for (Field field : nibbleClass.getDeclaredFields()) {
                if (field.getType() == byte[].class) {
                    if (nibbleDataField != null) {
                        throw new ProbeException("NibbleArray has more than one byte[] field");
                    }
                    nibbleDataField = field;
                }
            }
            if (nibbleDataField == null) {
                throw new ProbeException("NibbleArray data byte[] field not found in "
                        + nibbleClass.getName());
            }
            nibbleDataField.setAccessible(true);

            try {
                nibbleCtor = nibbleClass.getConstructor(int.class, int.class);
            } catch (NoSuchMethodException ex) {
                throw new ProbeException("NibbleArray (int, int) constructor not found", ex);
            }

            if (layout == NmsHandles.Layout.VANILLA) {
                msbField = findField(sectionClass, MSB_ARRAY, nibbleClass, "section MSB id array");
            }
        }

        Constructor<?> sectionCtor;
        try {
            sectionCtor = sectionClass.getConstructor(int.class, boolean.class);
        } catch (NoSuchMethodException ex) {
            throw new ProbeException("section (int, boolean) constructor not found", ex);
        }

        Method removeInvalidBlocks = findMethod(sectionClass, REMOVE_INVALID_BLOCKS,
                "section removeInvalidBlocks");

        return new NmsHandles(getHandle, getChunk, getSections,
                generateSkylightMap, setChunkModified, isModifiedField,
                tileEntityMapField, providerField, hasNoSkyField,
                relight, blockGetById, blockGetLightValue,
                layout, sectionCtor, removeInvalidBlocks,
                ids16Field, lsbField, msbField, metaField, meta16Field,
                nibbleCtor, nibbleDataField);
    }

    /**
     * Find a method by candidate names, walking the class hierarchy
     */
    private static Method findMethod(Class<?> cls, String[] names, String what,
            Class<?>... params) throws ProbeException {
        for (String name : names) {
            for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
                try {
                    Method method = c.getDeclaredMethod(name, params);
                    method.setAccessible(true);
                    return method;
                } catch (NoSuchMethodException ex) {
                    //Try the superclass / next candidate
                }
            }
        }
        throw new ProbeException(what + " not found on " + cls.getName());
    }

    /**
     * Find a field by candidate names, walking the class hierarchy;
     * null when no candidate matches
     */
    private static Field findFieldOrNull(Class<?> cls, String[] names,
            Class<?> expectedType) {
        try {
            return findField(cls, names, expectedType, "");
        } catch (ProbeException ex) {
            return null;
        }
    }

    /**
     * Find a field by candidate names, walking the class hierarchy. When
     * expectedType is not null the field type must match.
     */
    private static Field findField(Class<?> cls, String[] names, Class<?> expectedType,
            String what) throws ProbeException {
        for (String name : names) {
            for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
                try {
                    Field field = c.getDeclaredField(name);
                    if (expectedType != null && field.getType() != expectedType) {
                        continue;
                    }
                    field.setAccessible(true);
                    return field;
                } catch (NoSuchFieldException ex) {
                    //Try the superclass / next candidate
                }
            }
        }
        throw new ProbeException(what + " not found on " + cls.getName());
    }
}
