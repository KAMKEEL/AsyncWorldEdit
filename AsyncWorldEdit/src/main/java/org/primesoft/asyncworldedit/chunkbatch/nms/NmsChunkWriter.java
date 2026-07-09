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

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.bukkit.World;
import org.primesoft.asyncworldedit.chunkbatch.PendingChunk;
import org.primesoft.asyncworldedit.chunkbatch.PendingSection;
import org.primesoft.asyncworldedit.chunkbatch.SectionMath;

/**
 * Applies a {@link PendingChunk} directly to the NMS chunk of a Bukkit
 * world using the handles resolved by {@link NmsProbe}. Must only be
 * called from the server main thread.
 *
 * Per chunk the writer:
 * 1. writes the pending blocks into the chunk section arrays (creating
 *    missing sections),
 * 2. recalculates the section block reference counters
 *    (removeInvalidBlocks),
 * 3. invalidates and removes tile entities at overwritten positions so no
 *    stale tile data survives under the new blocks,
 * 4. regenerates the heightmap and sky light columns
 *    (generateSkylightMap),
 * 5. relights positions where a light emitting block was added or removed
 *    (bounded per chunk),
 * 6. marks the chunk modified so it gets saved and
 * 7. refreshes the chunk for all watching clients with one whole chunk
 *    packet (Bukkit refreshChunk).
 *
 * Blocks whose id does not fit the detected section layout are returned
 * to the caller for classic per block placement.
 *
 * @author KAMKEEL
 */
public class NmsChunkWriter {

    /**
     * Upper bound of expensive full relight calls per chunk
     */
    private static final int MAX_RELIGHT_PER_CHUNK = 256;

    /**
     * An overflow block that has to be placed through the classic path
     */
    public static final class OverflowBlock {

        public final int x;
        public final int y;
        public final int z;
        public final int id;
        public final int data;
        public final boolean notify;

        OverflowBlock(int x, int y, int z, int id, int data, boolean notify) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.id = id;
            this.data = data;
            this.notify = notify;
        }
    }

    private final NmsHandles m_handles;

    /**
     * Lazily resolved TileEntity.invalidate
     */
    private Method m_tileInvalidate;

    private boolean m_tileInvalidateResolved;

    /**
     * Lazily resolved ChunkPosition coordinate fields
     */
    private Field m_posXField;
    private Field m_posYField;
    private Field m_posZField;
    private boolean m_posFieldsResolved;

    /**
     * Light value per block id cache (-1 = unknown yet)
     */
    private int[] m_lightValues;

    /**
     * The section array layout was verified against a live section
     */
    private boolean m_layoutVerified;

    /**
     * Lazily resolved Spigot compact section fields (byte compactId /
     * byte compactData), null when the server has no section compaction
     */
    private Field m_compactIdField;
    private Field m_compactDataField;
    private boolean m_compactFieldsResolved;

    public NmsChunkWriter(NmsHandles handles) {
        m_handles = handles;
    }

    /**
     * Verify that the section arrays behind the detected layout have the
     * expected dimensions. A structural probe can be fooled by coremod
     * patched classes; a wrong pick must be refused before anything is
     * written, otherwise the world silently corrupts.
     *
     * @param section a live (non null) chunk section
     * @param handles the resolved handles
     * @return an error description, or null when the layout checks out
     * @throws Exception when the reflective access itself fails
     */
    public static String checkSectionArrays(Object section, NmsHandles handles)
            throws Exception {
        if (handles.layout == NmsHandles.Layout.ID16) {
            short[] ids16 = (short[]) handles.ids16Field.get(section);
            if (ids16 != null && ids16.length != SectionMath.SECTION_SIZE) {
                return String.format(
                        "section id array %s has %d entries, expected %d",
                        handles.ids16Field.getName(), ids16.length,
                        SectionMath.SECTION_SIZE);
            }
        } else {
            byte[] lsb = (byte[]) handles.lsbField.get(section);
            //A null id array is legal: Spigot 1.7.10 compacts uniform
            //sections (compactId/compactData) and only materializes the
            //array on demand
            if (lsb != null && lsb.length != SectionMath.SECTION_SIZE) {
                return String.format(
                        "section id array %s has %d entries, expected %d",
                        handles.lsbField.getName(), lsb.length,
                        SectionMath.SECTION_SIZE);
            }
        }

        if (handles.meta16Field != null) {
            short[] meta16 = (short[]) handles.meta16Field.get(section);
            if (meta16 != null && meta16.length != SectionMath.SECTION_SIZE) {
                return String.format(
                        "section metadata array %s has %d entries, expected %d",
                        handles.meta16Field.getName(), meta16.length,
                        SectionMath.SECTION_SIZE);
            }
        } else if (handles.metaField != null && handles.nibbleDataField != null) {
            Object metaNibble = handles.metaField.get(section);
            if (metaNibble != null) {
                byte[] meta = (byte[]) handles.nibbleDataField.get(metaNibble);
                if (meta == null || meta.length != SectionMath.NIBBLE_SIZE) {
                    return String.format(
                            "section metadata array has %d bytes, expected %d",
                            meta == null ? 0 : meta.length, SectionMath.NIBBLE_SIZE);
                }
            }
        }

        return null;
    }

    /**
     * The detected section layout
     */
    public NmsHandles.Layout getLayout() {
        return m_handles.layout;
    }

    /**
     * Apply all pending blocks of one chunk. Main thread only.
     *
     * @param world the Bukkit world
     * @param pending the pending chunk
     * @return blocks that could not be represented in the section layout
     * and must be placed through the classic path
     * @throws Exception when any reflective access fails; the caller
     * should fall back to classic placement
     */
    public List<OverflowBlock> apply(World world, PendingChunk pending) throws Exception {
        final int cx = pending.getX();
        final int cz = pending.getZ();

        //Make sure the chunk is loaded (Bukkit API, loads if needed)
        world.getChunkAt(cx, cz);

        final Object handle = m_handles.worldGetHandle.invoke(world);
        final Object chunk = m_handles.getChunk.invoke(handle, cx, cz);
        final Object[] sections = (Object[]) m_handles.getSections.invoke(chunk);

        final Object provider = m_handles.providerField.get(handle);
        final boolean hasSky = !m_handles.hasNoSkyField.getBoolean(provider);

        final List<OverflowBlock> overflow = new ArrayList<OverflowBlock>();
        final List<int[]> relightPositions = new ArrayList<int[]>();
        final boolean collectRelight = m_handles.relight != null
                && m_handles.blockGetById != null;

        final int bx = cx << 4;
        final int bz = cz << 4;

        for (int s = 0; s < SectionMath.SECTIONS_PER_CHUNK && s < sections.length; s++) {
            final PendingSection ps = pending.getSection(s);
            if (ps == null || ps.getCount() == 0) {
                continue;
            }

            Object section = sections[s];
            if (section == null) {
                if (ps.getNonAirCount() == 0) {
                    //Only air pending in a not existing section - nothing to do
                    continue;
                }
                section = m_handles.sectionCtor.newInstance(s << 4, hasSky);
                sections[s] = section;
            } else if (!m_layoutVerified) {
                //Verify the detected layout against the first live section
                //before anything is written - a wrong structural pick must
                //disable the feature, not corrupt the world
                String error = checkSectionArrays(section, m_handles);
                if (error != null) {
                    throw new IllegalStateException(
                            "section layout mismatch: " + error);
                }
                //Only count the layout as verified once a materialized id
                //array was seen (compact/uniform sections have none)
                if (hasIdArray(section)) {
                    m_layoutVerified = true;
                }
            }

            final int sectionY = s << 4;
            final PendingSection.IChangedBlockVisitor changed;
            if (collectRelight) {
                changed = new PendingSection.IChangedBlockVisitor() {
                    @Override
                    public void changed(int index, int oldId, int newId) {
                        if (relightPositions.size() >= MAX_RELIGHT_PER_CHUNK) {
                            return;
                        }
                        if (lightValueOf(oldId) > 0 || lightValueOf(newId) > 0) {
                            relightPositions.add(new int[]{
                                bx + SectionMath.indexToX(index),
                                sectionY + SectionMath.indexToY(index),
                                bz + SectionMath.indexToZ(index)});
                        }
                    }
                };
            } else {
                changed = null;
            }

            final PendingSection.IOverflowVisitor overflowVisitor
                    = new PendingSection.IOverflowVisitor() {
                        @Override
                        public void overflow(int index, int id, int data, boolean notify) {
                            overflow.add(new OverflowBlock(
                                    bx + SectionMath.indexToX(index),
                                    sectionY + SectionMath.indexToY(index),
                                    bz + SectionMath.indexToZ(index),
                                    id, data, notify));
                        }
                    };

            if (m_handles.layout == NmsHandles.Layout.ID16) {
                short[] ids16 = (short[]) m_handles.ids16Field.get(section);
                if (ids16 == null) {
                    ids16 = new short[SectionMath.SECTION_SIZE];
                    m_handles.ids16Field.set(section, ids16);
                }
                if (m_handles.meta16Field != null) {
                    //GTNH NEID: the metadata lives in a 16 bit short
                    //array; the vanilla NibbleArray is dead - writing it
                    //would silently drop every block's metadata
                    short[] meta16 = (short[]) m_handles.meta16Field.get(section);
                    if (meta16 == null) {
                        meta16 = new short[SectionMath.SECTION_SIZE];
                        m_handles.meta16Field.set(section, meta16);
                    }
                    ps.applyId16(ids16, meta16, changed, overflowVisitor);
                } else {
                    byte[] meta = ensureMetaArray(section);
                    ps.applyId16(ids16, meta, changed, overflowVisitor);
                }
            } else {
                byte[] lsb = ensureVanillaIdArray(section);
                if (lsb == null) {
                    //Compacted uniform section (Spigot) that we can not
                    //expand safely - route the whole section to the
                    //classic path instead of corrupting it
                    collectSectionOverflow(pending, s, overflow);
                    continue;
                }
                byte[] msb;
                if (m_handles.msbField.get(section) == null && ps.needsMsb()) {
                    Object nibble = m_handles.nibbleCtor.newInstance(
                            SectionMath.SECTION_SIZE, 4);
                    m_handles.msbField.set(section, nibble);
                }
                Object msbNibble = m_handles.msbField.get(section);
                msb = msbNibble != null
                        ? (byte[]) m_handles.nibbleDataField.get(msbNibble) : null;
                byte[] meta = ensureMetaArray(section);
                ps.applyVanilla(lsb, msb, meta, changed, overflowVisitor);
            }

            m_handles.removeInvalidBlocks.invoke(section);
        }

        //Sections the chunk cannot hold (shorter section array than the
        //vanilla 16) must not be dropped - route them to the classic path
        for (int s = sections.length; s < SectionMath.SECTIONS_PER_CHUNK; s++) {
            collectSectionOverflow(pending, s, overflow);
        }

        removeStaleTileEntities(chunk, pending);

        m_handles.generateSkylightMap.invoke(chunk);

        if (m_handles.setChunkModified != null) {
            m_handles.setChunkModified.invoke(chunk);
        } else {
            m_handles.isModifiedField.setBoolean(chunk, true);
        }

        for (int[] pos : relightPositions) {
            m_handles.relight.invoke(handle, pos[0], pos[1], pos[2]);
        }

        //One whole chunk refresh for all watching clients
        world.refreshChunk(cx, cz);

        return overflow;
    }

    /**
     * True when the section has a materialized id array for the detected
     * layout
     */
    private boolean hasIdArray(Object section) throws Exception {
        if (m_handles.layout == NmsHandles.Layout.ID16) {
            return m_handles.ids16Field.get(section) != null;
        }
        return m_handles.lsbField.get(section) != null;
    }

    /**
     * Get the vanilla LSB id array of a section, expanding a Spigot
     * compacted uniform section (null array + compactId byte) when
     * needed.
     *
     * @return the id array, or null when the section is compacted and no
     * compact fields could be resolved (the caller must fall back to the
     * classic path for this section - overwriting a uniform section with
     * a zero array would erase it)
     */
    byte[] ensureVanillaIdArray(Object section) throws Exception {
        byte[] lsb = (byte[]) m_handles.lsbField.get(section);
        if (lsb != null) {
            return lsb;
        }

        resolveCompactFields(section.getClass());
        if (m_compactIdField == null) {
            return null;
        }

        lsb = new byte[SectionMath.SECTION_SIZE];
        byte compactId = m_compactIdField.getByte(section);
        if (compactId != 0) {
            java.util.Arrays.fill(lsb, compactId);
        }
        m_handles.lsbField.set(section, lsb);
        m_compactIdField.setByte(section, (byte) 0);
        return lsb;
    }

    /**
     * Get the metadata nibble array of a section, creating the
     * NibbleArray when missing. A Spigot compacted metadata value
     * (compactData byte) is expanded into the new array so the uniform
     * metadata of untouched blocks survives.
     */
    byte[] ensureMetaArray(Object section) throws Exception {
        Object nibble = m_handles.metaField.get(section);
        if (nibble != null) {
            return (byte[]) m_handles.nibbleDataField.get(nibble);
        }

        nibble = m_handles.nibbleCtor.newInstance(SectionMath.SECTION_SIZE, 4);
        m_handles.metaField.set(section, nibble);
        byte[] data = (byte[]) m_handles.nibbleDataField.get(nibble);

        resolveCompactFields(section.getClass());
        if (m_compactDataField != null) {
            int compactData = m_compactDataField.getByte(section) & 0xF;
            if (compactData != 0) {
                byte packed = (byte) (compactData | (compactData << 4));
                java.util.Arrays.fill(data, packed);
            }
            m_compactDataField.setByte(section, (byte) 0);
        }

        return data;
    }

    /**
     * Resolve the optional Spigot 1.7.10 compact section fields once
     * (byte compactId / byte compactData on ChunkSection)
     */
    private void resolveCompactFields(Class<?> sectionClass) {
        if (m_compactFieldsResolved) {
            return;
        }
        m_compactFieldsResolved = true;

        Field compactId = findAnyField(sectionClass, "compactId");
        Field compactData = findAnyField(sectionClass, "compactData");
        if (compactId != null && compactId.getType() == byte.class
                && compactData != null && compactData.getType() == byte.class) {
            m_compactIdField = compactId;
            m_compactDataField = compactData;
        }
    }

    /**
     * Collect all pending blocks of one section as overflow blocks for
     * the classic per block path
     *
     * @param pending the pending chunk
     * @param sectionIdx the section (0..15)
     * @param out the overflow list to append to
     */
    static void collectSectionOverflow(PendingChunk pending, int sectionIdx,
            List<OverflowBlock> out) {
        PendingSection ps = pending.getSection(sectionIdx);
        if (ps == null || ps.getCount() == 0) {
            return;
        }

        final int bx = pending.getX() << 4;
        final int bz = pending.getZ() << 4;
        final int sy = sectionIdx << 4;

        for (int index = 0; index < SectionMath.SECTION_SIZE; index++) {
            int slot = ps.getSlot(index);
            if (slot == SectionMath.EMPTY_SLOT) {
                continue;
            }

            out.add(new OverflowBlock(
                    bx + SectionMath.indexToX(index),
                    sy + SectionMath.indexToY(index),
                    bz + SectionMath.indexToZ(index),
                    SectionMath.slotId(slot), SectionMath.slotData(slot),
                    SectionMath.slotNotify(slot)));
        }
    }

    /**
     * Invalidate and remove tile entities located at overwritten positions.
     * A stale tile entity under a directly written block is exactly the
     * kind of ghost data this must prevent.
     *
     * The matching entries are snapshotted first, then removed from the
     * map, and invalidate() runs last and outside any iteration: a modded
     * invalidate() may mutate the chunk tile entity map, which would throw
     * a ConcurrentModificationException mid iteration (and permanently
     * disable the feature).
     */
    void removeStaleTileEntities(Object chunk, PendingChunk pending) throws Exception {
        Map<?, ?> tiles = (Map<?, ?>) m_handles.tileEntityMapField.get(chunk);
        if (tiles == null || tiles.isEmpty()) {
            return;
        }

        List<Object> staleKeys = null;
        List<Object> staleTiles = null;

        for (Map.Entry<?, ?> entry : tiles.entrySet()) {
            Object pos = entry.getKey();

            resolvePosFields(pos.getClass());
            if (m_posXField == null) {
                //Unknown key layout - leave the tile entities alone
                return;
            }

            int lx = m_posXField.getInt(pos) & 0xF;
            int y = m_posYField.getInt(pos);
            int lz = m_posZField.getInt(pos) & 0xF;

            if (pending.getPendingSlotLocal(lx, y, lz) == SectionMath.EMPTY_SLOT) {
                continue;
            }

            if (staleKeys == null) {
                staleKeys = new ArrayList<Object>();
                staleTiles = new ArrayList<Object>();
            }
            staleKeys.add(pos);
            staleTiles.add(entry.getValue());
        }

        if (staleKeys == null) {
            return;
        }

        for (Object key : staleKeys) {
            tiles.remove(key);
        }

        //Invalidate outside the map iteration - it may mutate the map
        for (Object tile : staleTiles) {
            invalidateTile(tile);
        }
    }

    /**
     * Resolve the ChunkPosition coordinate fields once
     */
    private void resolvePosFields(Class<?> posClass) {
        if (m_posFieldsResolved) {
            return;
        }
        m_posFieldsResolved = true;

        m_posXField = findAnyField(posClass, "field_151329_a", "chunkPosX", "x");
        m_posYField = findAnyField(posClass, "field_151327_b", "chunkPosY", "y");
        m_posZField = findAnyField(posClass, "field_151328_c", "chunkPosZ", "z");

        if (m_posXField == null || m_posYField == null || m_posZField == null) {
            m_posXField = null;
            m_posYField = null;
            m_posZField = null;
        }
    }

    /**
     * Call TileEntity.invalidate() when resolvable; the map removal alone
     * already detaches the tile from the chunk
     */
    private void invalidateTile(Object tile) {
        if (tile == null) {
            return;
        }

        if (!m_tileInvalidateResolved) {
            m_tileInvalidateResolved = true;
            for (String name : new String[]{"func_145843_s", "invalidate"}) {
                for (Class<?> c = tile.getClass(); c != null && c != Object.class;
                        c = c.getSuperclass()) {
                    try {
                        Method method = c.getDeclaredMethod(name);
                        method.setAccessible(true);
                        m_tileInvalidate = method;
                        break;
                    } catch (NoSuchMethodException ex) {
                        //Try the superclass / next candidate
                    }
                }
                if (m_tileInvalidate != null) {
                    break;
                }
            }
        }

        if (m_tileInvalidate != null) {
            try {
                m_tileInvalidate.invoke(tile);
            } catch (Exception ex) {
                //The tile is removed from the chunk map anyway
            }
        }
    }

    /**
     * The light emission value of a block id (cached)
     */
    private int lightValueOf(int id) {
        if (id < 0 || id > SectionMath.ID16_MAX_ID || m_handles.blockGetById == null) {
            return 0;
        }

        if (m_lightValues == null) {
            m_lightValues = new int[SectionMath.ID16_MAX_ID + 1];
            java.util.Arrays.fill(m_lightValues, -1);
        }

        int cached = m_lightValues[id];
        if (cached >= 0) {
            return cached;
        }

        int value = 0;
        try {
            Object block = m_handles.blockGetById.invoke(null, id);
            if (block != null) {
                value = ((Number) m_handles.blockGetLightValue.invoke(block)).intValue();
            }
        } catch (Exception ex) {
            value = 0;
        }

        m_lightValues[id] = value;
        return value;
    }

    private static Field findAnyField(Class<?> cls, String... names) {
        for (String name : names) {
            for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
                try {
                    Field field = c.getDeclaredField(name);
                    field.setAccessible(true);
                    return field;
                } catch (NoSuchFieldException ex) {
                    //Try the superclass / next candidate
                }
            }
        }
        return null;
    }
}
