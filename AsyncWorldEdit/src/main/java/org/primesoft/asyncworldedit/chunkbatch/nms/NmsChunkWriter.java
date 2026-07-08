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
import java.util.Iterator;
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

    public NmsChunkWriter(NmsHandles handles) {
        m_handles = handles;
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
                byte[] meta = nibbleData(section, m_handles.metaField, true);
                ps.applyId16(ids16, meta, changed, overflowVisitor);
            } else {
                byte[] lsb = (byte[]) m_handles.lsbField.get(section);
                byte[] msb;
                if (m_handles.msbField.get(section) == null && ps.needsMsb()) {
                    Object nibble = m_handles.nibbleCtor.newInstance(
                            SectionMath.SECTION_SIZE, 4);
                    m_handles.msbField.set(section, nibble);
                }
                Object msbNibble = m_handles.msbField.get(section);
                msb = msbNibble != null
                        ? (byte[]) m_handles.nibbleDataField.get(msbNibble) : null;
                byte[] meta = nibbleData(section, m_handles.metaField, true);
                ps.applyVanilla(lsb, msb, meta, changed, overflowVisitor);
            }

            m_handles.removeInvalidBlocks.invoke(section);
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
     * Get the raw byte array of a NibbleArray typed section field,
     * creating the NibbleArray when missing and requested
     */
    private byte[] nibbleData(Object section, Field nibbleField, boolean create)
            throws Exception {
        Object nibble = nibbleField.get(section);
        if (nibble == null) {
            if (!create) {
                return null;
            }
            nibble = m_handles.nibbleCtor.newInstance(SectionMath.SECTION_SIZE, 4);
            nibbleField.set(section, nibble);
        }
        return (byte[]) m_handles.nibbleDataField.get(nibble);
    }

    /**
     * Invalidate and remove tile entities located at overwritten positions.
     * A stale tile entity under a directly written block is exactly the
     * kind of ghost data this must prevent.
     */
    private void removeStaleTileEntities(Object chunk, PendingChunk pending) throws Exception {
        Map<?, ?> tiles = (Map<?, ?>) m_handles.tileEntityMapField.get(chunk);
        if (tiles == null || tiles.isEmpty()) {
            return;
        }

        Iterator<? extends Map.Entry<?, ?>> iterator = tiles.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<?, ?> entry = iterator.next();
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

            Object tile = entry.getValue();
            invalidateTile(tile);
            iterator.remove();
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
