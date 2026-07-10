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

/**
 * Coordinate, index, nibble and slot-encoding math for chunk batched
 * block placement. Pure static functions, no Bukkit/NMS/WorldEdit imports.
 *
 * A chunk section holds 16x16x16 blocks. The linear index inside a section
 * is (y &amp; 15) &lt;&lt; 8 | (z &amp; 15) &lt;&lt; 4 | (x &amp; 15), matching the
 * layout of the vanilla 1.7.10 ExtendedBlockStorage arrays.
 *
 * @author KAMKEEL
 */
public final class SectionMath {

    /**
     * Number of blocks in one chunk section (16x16x16)
     */
    public static final int SECTION_SIZE = 4096;

    /**
     * Number of bytes in a nibble array covering one section
     */
    public static final int NIBBLE_SIZE = SECTION_SIZE / 2;

    /**
     * Number of sections in a chunk column (world height 256)
     */
    public static final int SECTIONS_PER_CHUNK = 16;

    /**
     * Highest block id storable in the vanilla LSB byte + MSB nibble layout
     * (12 bits)
     */
    public static final int VANILLA_MAX_ID = 0xFFF;

    /**
     * Highest block id storable in the NotEnoughIDs short array layout
     * (16 bits)
     */
    public static final int ID16_MAX_ID = 0xFFFF;

    /**
     * Highest block metadata value (4 bits)
     */
    public static final int MAX_DATA = 0xF;

    /**
     * Marker for an empty pending slot
     */
    public static final int EMPTY_SLOT = -1;

    private SectionMath() {
    }

    /**
     * Convert a world block coordinate to a chunk coordinate
     */
    public static int blockToChunk(int blockCoord) {
        return blockCoord >> 4;
    }

    /**
     * Convert a world block coordinate to a coordinate local to its chunk
     * (0..15)
     */
    public static int blockToLocal(int blockCoord) {
        return blockCoord & 0xF;
    }

    /**
     * The section (0..15) a block y coordinate belongs to
     */
    public static int sectionOfY(int y) {
        return y >> 4;
    }

    /**
     * The linear index of a block inside its section. Accepts world or
     * local coordinates (they are masked to the low 4 bits).
     */
    public static int sectionIndex(int x, int y, int z) {
        return ((y & 0xF) << 8) | ((z & 0xF) << 4) | (x & 0xF);
    }

    /**
     * The local x coordinate encoded in a section index
     */
    public static int indexToX(int index) {
        return index & 0xF;
    }

    /**
     * The local z coordinate encoded in a section index
     */
    public static int indexToZ(int index) {
        return (index >> 4) & 0xF;
    }

    /**
     * The local (in-section) y coordinate encoded in a section index
     */
    public static int indexToY(int index) {
        return (index >> 8) & 0xF;
    }

    /**
     * Pack chunk coordinates into a single map key. Safe for negative
     * coordinates.
     */
    public static long chunkKey(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }

    /**
     * The chunk x coordinate stored in a chunk key
     */
    public static int chunkKeyX(long key) {
        return (int) (key >> 32);
    }

    /**
     * The chunk z coordinate stored in a chunk key
     */
    public static int chunkKeyZ(long key) {
        return (int) key;
    }

    /**
     * Read a nibble from a packed nibble array. Even indices occupy the low
     * nibble of a byte, odd indices the high nibble (vanilla NibbleArray
     * layout).
     */
    public static int nibbleGet(byte[] data, int index) {
        int b = data[index >> 1] & 0xFF;
        return (index & 1) == 0 ? (b & 0xF) : (b >> 4);
    }

    /**
     * Write a nibble into a packed nibble array. Even indices occupy the low
     * nibble of a byte, odd indices the high nibble (vanilla NibbleArray
     * layout).
     */
    public static void nibbleSet(byte[] data, int index, int value) {
        int i = index >> 1;
        if ((index & 1) == 0) {
            data[i] = (byte) ((data[i] & 0xF0) | (value & 0xF));
        } else {
            data[i] = (byte) ((data[i] & 0x0F) | ((value & 0xF) << 4));
        }
    }

    /**
     * Encode a pending block into a slot value. Bit 0 is the notify flag,
     * bits 1..4 the metadata, bits 5 and up the block id. The result is
     * always &gt;= 0 so {@link #EMPTY_SLOT} can mark unused slots.
     *
     * <p>
     * ID CEILING INVARIANT: the slot layout physically admits 20-bit ids,
     * but every id the engine handles is at most 16 bits - the NEID
     * ceiling is Short.MAX_VALUE (the write path caps at
     * {@link #ID16_MAX_ID} / {@link #VANILLA_MAX_ID} per layout) and the
     * columnar undo run encoding packs ids into 16 bits
     * (ColumnarUndoLog.capture masks defensively and warns once). The
     * headroom here is layout slack, not a supported range: anything
     * introducing ids above 0xFFFF must widen the undo run encoding
     * first.</p>
     */
    public static int encodeSlot(int id, int data, boolean notify) {
        return ((id & 0xFFFFF) << 5) | ((data & 0xF) << 1) | (notify ? 1 : 0);
    }

    /**
     * The block id stored in a slot value
     */
    public static int slotId(int slot) {
        return slot >>> 5;
    }

    /**
     * The block metadata stored in a slot value
     */
    public static int slotData(int slot) {
        return (slot >> 1) & 0xF;
    }

    /**
     * The notify (physics/light on classic replay) flag stored in a slot
     * value
     */
    public static boolean slotNotify(int slot) {
        return (slot & 1) != 0;
    }
}
