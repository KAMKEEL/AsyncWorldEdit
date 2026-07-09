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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import static org.junit.Assert.*;
import org.junit.Test;
import org.primesoft.asyncworldedit.chunkbatch.PendingChunk;
import org.primesoft.asyncworldedit.chunkbatch.SectionMath;

/**
 * Pure logic tests for the NMS chunk writer helpers: the section array
 * sanity check, the out of range section overflow and the snapshot based
 * tile entity removal (a modded invalidate() may mutate the tile entity
 * map - that must never throw a ConcurrentModificationException).
 *
 * @author KAMKEEL
 */
public class NmsChunkWriterTest {

    //---------------------------------------------------------------------
    //Section array sanity check (uses the fake hierarchies of NmsProbeTest)
    //---------------------------------------------------------------------
    @Test
    public void sectionCheckPassesOnHealthyVanillaSection() throws Exception {
        NmsHandles handles = NmsProbe.probe(NmsProbeTest.FakeCraftWorldVanilla.class);
        NmsProbeTest.FakeSectionVanilla section
                = new NmsProbeTest.FakeSectionVanilla(0, true);

        assertNull(NmsChunkWriter.checkSectionArrays(section, handles));
    }

    @Test
    public void sectionCheckRefusesWrongLsbArrayLength() throws Exception {
        NmsHandles handles = NmsProbe.probe(NmsProbeTest.FakeCraftWorldVanilla.class);
        NmsProbeTest.FakeSectionVanilla section
                = new NmsProbeTest.FakeSectionVanilla(0, true);
        //A wrongly detected byte[] (or a repacked coremod array) will not
        //have the 4096 entries of a real id array
        section.blockLSBArray = new byte[16];

        String error = NmsChunkWriter.checkSectionArrays(section, handles);
        assertNotNull(error);
        assertTrue(error.contains("4096"));
    }

    @Test
    public void sectionCheckRefusesWrongId16ArrayLength() throws Exception {
        NmsHandles handles = NmsProbe.probe(NmsProbeTest.FakeCraftWorldNeid.class);
        NmsProbeTest.FakeSectionNeid section
                = new NmsProbeTest.FakeSectionNeid(0, true);
        section.block16BArray = new short[64];

        String error = NmsChunkWriter.checkSectionArrays(section, handles);
        assertNotNull(error);
        assertTrue(error.contains("4096"));
    }

    @Test
    public void sectionCheckRefusesWrongMetadataLength() throws Exception {
        NmsHandles handles = NmsProbe.probe(NmsProbeTest.FakeCraftWorldVanilla.class);
        NmsProbeTest.FakeSectionVanilla section
                = new NmsProbeTest.FakeSectionVanilla(0, true);
        section.blockMetadataArray.data = new byte[100];

        String error = NmsChunkWriter.checkSectionArrays(section, handles);
        assertNotNull(error);
        assertTrue(error.contains("2048"));
    }

    @Test
    public void sectionCheckValidatesGtnhWideMetadata() throws Exception {
        NmsHandles handles = NmsProbe.probe(NmsProbeTest.FakeCraftWorldNeidGtnh.class);
        NmsProbeTest.FakeSectionNeidGtnh section
                = new NmsProbeTest.FakeSectionNeidGtnh(0, true);

        //Healthy GTNH NEID section (both short arrays 4096 long)
        assertNull(NmsChunkWriter.checkSectionArrays(section, handles));

        //A wrong length 16 bit metadata array must be refused before the
        //first write
        section.block16BMetaArray = new short[64];
        String error = NmsChunkWriter.checkSectionArrays(section, handles);
        assertNotNull(error);
        assertTrue(error.contains("4096"));
        assertTrue(error.contains("block16BMetaArray"));
    }

    //---------------------------------------------------------------------
    //Spigot 1.7.10 compact section support: uniform sections keep a null
    //id array plus compactId/compactData bytes (FAWE's BukkitQueue17
    //handles the same fields). Writing a fresh zero array over such a
    //section would erase it.
    //---------------------------------------------------------------------
    public static class FakeSectionSpigotCompact {

        public byte[] blockIds;
        public NmsProbeTest.FakeNibble extBlockIds;
        public NmsProbeTest.FakeNibble blockData;
        public byte compactId;
        public byte compactData;

        public FakeSectionSpigotCompact(int yBase, boolean hasSky) {
        }

        public void recalcBlockCounts() {
        }
    }

    public static class FakeChunkSpigotCompact {

        public Map<Object, Object> tileEntities = new HashMap<Object, Object>();

        public FakeSectionSpigotCompact[] getSections() {
            return new FakeSectionSpigotCompact[16];
        }

        public void initLighting() {
        }

        public void e() {
        }
    }

    public static class FakeWorldSpigotCompact {

        public NmsProbeTest.FakeProviderCb worldProvider = new NmsProbeTest.FakeProviderCb();

        public FakeChunkSpigotCompact getChunkAt(int x, int z) {
            return null;
        }
    }

    public static class FakeCraftWorldSpigotCompact {

        public FakeWorldSpigotCompact getHandle() {
            return null;
        }
    }

    @Test
    public void compactSectionIsExpandedNotErased() throws Exception {
        NmsHandles handles = NmsProbe.probe(FakeCraftWorldSpigotCompact.class);
        NmsChunkWriter writer = new NmsChunkWriter(handles);

        //A uniform all-stone section with metadata 2, fully compacted
        FakeSectionSpigotCompact section = new FakeSectionSpigotCompact(0, true);
        section.compactId = 1;
        section.compactData = 2;

        byte[] lsb = writer.ensureVanillaIdArray(section);
        assertNotNull(lsb);
        assertEquals(SectionMath.SECTION_SIZE, lsb.length);
        //Every block of the uniform section must survive the expansion
        for (int i = 0; i < lsb.length; i++) {
            assertEquals(1, lsb[i]);
        }
        assertSame(lsb, section.blockIds);
        assertEquals("compact marker must be cleared", 0, section.compactId);

        byte[] meta = writer.ensureMetaArray(section);
        assertNotNull(meta);
        assertEquals(SectionMath.NIBBLE_SIZE, meta.length);
        for (int i = 0; i < SectionMath.SECTION_SIZE; i++) {
            assertEquals(2, SectionMath.nibbleGet(meta, i));
        }
        assertEquals(0, section.compactData);

        //Already materialized arrays are returned as-is
        assertSame(lsb, writer.ensureVanillaIdArray(section));
    }

    @Test
    public void unknownCompactionIsRefusedNotZeroed() throws Exception {
        NmsHandles handles = NmsProbe.probe(NmsProbeTest.FakeCraftWorldVanilla.class);
        NmsChunkWriter writer = new NmsChunkWriter(handles);

        //A null id array on a class WITHOUT compact fields: expanding to
        //a zero array would erase whatever mechanism holds the blocks -
        //the writer must refuse so the section goes to the classic path
        NmsProbeTest.FakeSectionVanilla section
                = new NmsProbeTest.FakeSectionVanilla(0, true);
        section.blockLSBArray = null;

        assertNull(writer.ensureVanillaIdArray(section));
        assertNull("the section must stay untouched", section.blockLSBArray);
    }

    @Test
    public void sectionCheckAcceptsCompactedNullIdArray() throws Exception {
        NmsHandles handles = NmsProbe.probe(NmsProbeTest.FakeCraftWorldVanilla.class);
        NmsProbeTest.FakeSectionVanilla section
                = new NmsProbeTest.FakeSectionVanilla(0, true);
        section.blockLSBArray = null;

        //Null is a legal (compacted) state - only a wrong LENGTH means
        //the probe picked the wrong field
        assertNull(NmsChunkWriter.checkSectionArrays(section, handles));
    }

    //---------------------------------------------------------------------
    //Out of range section overflow
    //---------------------------------------------------------------------
    @Test
    public void outOfRangeSectionsGoToOverflowNotDropped() {
        PendingChunk pending = new PendingChunk(2, -1);
        //Section 15 (y 240..255) - a chunk with a shorter section array
        //cannot hold these, they must be routed to the classic path
        assertTrue(pending.setBlock(2 << 4, 250, -16, 89, 5, true));
        assertTrue(pending.setBlock((2 << 4) + 3, 240, -1, 20, 0, false));

        List<NmsChunkWriter.OverflowBlock> overflow
                = new ArrayList<NmsChunkWriter.OverflowBlock>();
        NmsChunkWriter.collectSectionOverflow(pending, 15, overflow);

        assertEquals(2, overflow.size());

        //Section local iteration is y-major, so y 240 comes first
        NmsChunkWriter.OverflowBlock first = overflow.get(0);
        assertEquals((2 << 4) + 3, first.x);
        assertEquals(240, first.y);
        assertEquals(-1, first.z);
        assertEquals(20, first.id);
        assertEquals(0, first.data);
        assertFalse(first.notify);

        NmsChunkWriter.OverflowBlock second = overflow.get(1);
        assertEquals(2 << 4, second.x);
        assertEquals(250, second.y);
        assertEquals(-16, second.z);
        assertEquals(89, second.id);
        assertEquals(5, second.data);
        assertTrue(second.notify);

        //Sections without pending blocks add nothing
        NmsChunkWriter.collectSectionOverflow(pending, 3, overflow);
        assertEquals(2, overflow.size());
    }

    //---------------------------------------------------------------------
    //Tile entity removal
    //---------------------------------------------------------------------
    public static class FakePos {

        public int x;
        public int y;
        public int z;

        FakePos(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    /**
     * A modded tile entity whose invalidate() mutates the chunk tile
     * entity map (this happens with multiblock machines)
     */
    public static class FakeTile {

        boolean invalidated;

        private final Map<Object, Object> m_chunkMap;

        FakeTile(Map<Object, Object> chunkMap) {
            m_chunkMap = chunkMap;
        }

        public void invalidate() {
            invalidated = true;
            //Mutate the map the caller might still be iterating
            m_chunkMap.put(new FakePos(9, 9, 9), "side effect");
        }
    }

    @Test
    public void staleTileRemovalSurvivesInvalidateMutatingTheMap() throws Exception {
        NmsHandles handles = NmsProbe.probe(NmsProbeTest.FakeCraftWorldVanilla.class);
        NmsChunkWriter writer = new NmsChunkWriter(handles);

        NmsProbeTest.FakeChunkVanilla chunk = new NmsProbeTest.FakeChunkVanilla();
        FakeTile stale = new FakeTile(chunk.chunkTileEntityMap);
        FakeTile untouched = new FakeTile(chunk.chunkTileEntityMap);
        FakePos stalePos = new FakePos(5, 64, 5);
        FakePos untouchedPos = new FakePos(6, 64, 6);
        chunk.chunkTileEntityMap.put(stalePos, stale);
        chunk.chunkTileEntityMap.put(untouchedPos, untouched);

        PendingChunk pending = new PendingChunk(0, 0);
        assertTrue(pending.setBlock(5, 64, 5, 1, 0, false));

        //Must not throw a ConcurrentModificationException even though
        //invalidate() mutates the map
        writer.removeStaleTileEntities(chunk, pending);

        assertTrue("tile under the overwritten position must be invalidated",
                stale.invalidated);
        assertFalse("unrelated tile must stay untouched", untouched.invalidated);
        assertFalse(chunk.chunkTileEntityMap.containsKey(stalePos));
        assertTrue(chunk.chunkTileEntityMap.containsKey(untouchedPos));
        //The side effect entry written by invalidate() survives
        assertEquals(2, chunk.chunkTileEntityMap.size());
    }

    @Test
    public void tileRemovalMatchesOnLocalCoordinates() throws Exception {
        NmsHandles handles = NmsProbe.probe(NmsProbeTest.FakeCraftWorldVanilla.class);
        NmsChunkWriter writer = new NmsChunkWriter(handles);

        //Chunk (2, 3): tile keys may store world coordinates - matching
        //must use the chunk local position
        NmsProbeTest.FakeChunkVanilla chunk = new NmsProbeTest.FakeChunkVanilla();
        Map<Object, Object> map = chunk.chunkTileEntityMap;
        FakeTile stale = new FakeTile(new HashMap<Object, Object>());
        map.put(new FakePos((2 << 4) + 5, 64, (3 << 4) + 5), stale);

        PendingChunk pending = new PendingChunk(2, 3);
        assertTrue(pending.setBlock((2 << 4) + 5, 64, (3 << 4) + 5, 1, 0, false));

        writer.removeStaleTileEntities(chunk, pending);

        assertTrue(stale.invalidated);
        assertTrue(map.isEmpty());
    }
}
