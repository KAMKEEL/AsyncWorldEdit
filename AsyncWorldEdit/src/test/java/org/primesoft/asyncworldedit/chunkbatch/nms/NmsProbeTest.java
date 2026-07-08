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

import java.util.HashMap;
import java.util.Map;
import static org.junit.Assert.*;
import org.junit.Test;

/**
 * Tests for the structural NMS capability probe using fake class
 * hierarchies that mimic the vanilla and the NotEnoughIDs patched
 * 1.7.10 chunk classes. Also asserts the clean fallback: every failure
 * is reported as a ProbeException with a message, never as an
 * uncontrolled exception.
 *
 * @author KAMKEEL
 */
public class NmsProbeTest {

    //---------------------------------------------------------------------
    //Fake vanilla hierarchy (MCP style names)
    //---------------------------------------------------------------------
    public static class FakeNibble {

        public byte[] data;

        public FakeNibble(int size, int depth) {
            data = new byte[size / 2];
        }
    }

    public static class FakeSectionVanilla {

        public byte[] blockLSBArray = new byte[4096];
        public FakeNibble blockMSBArray;
        public FakeNibble blockMetadataArray = new FakeNibble(4096, 4);
        public FakeNibble blocklightArray = new FakeNibble(4096, 4);
        public FakeNibble skylightArray = new FakeNibble(4096, 4);

        public FakeSectionVanilla(int yBase, boolean hasSky) {
        }

        public void removeInvalidBlocks() {
        }
    }

    public static class FakeProvider {

        public boolean hasNoSky;
    }

    public static class FakeChunkVanilla {

        public Map<Object, Object> chunkTileEntityMap = new HashMap<Object, Object>();

        public FakeSectionVanilla[] getBlockStorageArray() {
            return new FakeSectionVanilla[16];
        }

        public void generateSkylightMap() {
        }

        public void setChunkModified() {
        }
    }

    public static class FakeWorldVanilla {

        public FakeProvider provider = new FakeProvider();

        public FakeChunkVanilla getChunkFromChunkCoords(int x, int z) {
            return new FakeChunkVanilla();
        }
    }

    public static class FakeCraftWorldVanilla {

        public FakeWorldVanilla getHandle() {
            return new FakeWorldVanilla();
        }
    }

    //---------------------------------------------------------------------
    //Fake NotEnoughIDs hierarchy: short[] id array; the leftover LSB
    //byte array is still declared (like the real patched class may keep)
    //and must NOT win over the short[] detection. Uses the isModified
    //field instead of setChunkModified to cover that probe path.
    //---------------------------------------------------------------------
    public static class FakeSectionNeid {

        public short[] block16BArray = new short[4096];
        public byte[] blockLSBArray = new byte[4096];
        public FakeNibble blockMetadataArray = new FakeNibble(4096, 4);
        public FakeNibble blocklightArray = new FakeNibble(4096, 4);
        public FakeNibble skylightArray = new FakeNibble(4096, 4);

        public FakeSectionNeid(int yBase, boolean hasSky) {
        }

        public void removeInvalidBlocks() {
        }
    }

    public static class FakeChunkNeid {

        public Map<Object, Object> chunkTileEntityMap = new HashMap<Object, Object>();
        public boolean isModified;

        public FakeSectionNeid[] getBlockStorageArray() {
            return new FakeSectionNeid[16];
        }

        public void generateSkylightMap() {
        }
    }

    public static class FakeWorldNeid {

        public FakeProvider provider = new FakeProvider();

        public FakeChunkNeid getChunkFromChunkCoords(int x, int z) {
            return new FakeChunkNeid();
        }
    }

    public static class FakeCraftWorldNeid {

        public FakeWorldNeid getHandle() {
            return new FakeWorldNeid();
        }
    }

    //---------------------------------------------------------------------
    //Broken hierarchies for the clean fallback tests
    //---------------------------------------------------------------------
    public static class FakeSectionNoMeta {

        public byte[] blockLSBArray = new byte[4096];

        public FakeSectionNoMeta(int yBase, boolean hasSky) {
        }

        public void removeInvalidBlocks() {
        }
    }

    public static class FakeChunkNoMeta {

        public Map<Object, Object> chunkTileEntityMap = new HashMap<Object, Object>();

        public FakeSectionNoMeta[] getBlockStorageArray() {
            return new FakeSectionNoMeta[16];
        }

        public void generateSkylightMap() {
        }

        public void setChunkModified() {
        }
    }

    public static class FakeWorldNoMeta {

        public FakeProvider provider = new FakeProvider();

        public FakeChunkNoMeta getChunkFromChunkCoords(int x, int z) {
            return null;
        }
    }

    public static class FakeCraftWorldNoMeta {

        public FakeWorldNoMeta getHandle() {
            return null;
        }
    }

    public static class FakeChunkNoSections {

        public void generateSkylightMap() {
        }
    }

    public static class FakeWorldNoSections {

        public FakeChunkNoSections getChunkFromChunkCoords(int x, int z) {
            return null;
        }
    }

    public static class FakeCraftWorldNoSections {

        public FakeWorldNoSections getHandle() {
            return null;
        }
    }

    //---------------------------------------------------------------------
    //Tests
    //---------------------------------------------------------------------
    @Test
    public void probeDetectsVanillaLayout() throws ProbeException {
        NmsHandles handles = NmsProbe.probe(FakeCraftWorldVanilla.class);

        assertEquals(NmsHandles.Layout.VANILLA, handles.layout);
        assertNotNull(handles.worldGetHandle);
        assertNotNull(handles.getChunk);
        assertNotNull(handles.getSections);
        assertNotNull(handles.generateSkylightMap);
        assertNotNull(handles.setChunkModified);
        assertNotNull(handles.tileEntityMapField);
        assertNotNull(handles.providerField);
        assertNotNull(handles.hasNoSkyField);
        assertNotNull(handles.sectionCtor);
        assertNotNull(handles.removeInvalidBlocks);
        assertNotNull(handles.nibbleCtor);
        assertNotNull(handles.nibbleDataField);

        assertNull(handles.ids16Field);
        assertNotNull(handles.lsbField);
        assertNotNull(handles.msbField);
        assertNotNull(handles.metaField);

        assertEquals("blockLSBArray", handles.lsbField.getName());
        assertEquals("blockMSBArray", handles.msbField.getName());
        assertEquals("blockMetadataArray", handles.metaField.getName());
        assertEquals("data", handles.nibbleDataField.getName());
    }

    @Test
    public void probeDetectsNeidLayoutEvenWithLeftoverByteArray() throws ProbeException {
        NmsHandles handles = NmsProbe.probe(FakeCraftWorldNeid.class);

        //The short[] must win: writing the leftover 12 bit arrays on a
        //NotEnoughIDs server is exactly the id truncation bug class
        assertEquals(NmsHandles.Layout.ID16, handles.layout);
        assertNotNull(handles.ids16Field);
        assertEquals("block16BArray", handles.ids16Field.getName());
        assertNull("MSB array must not be used in the 16 bit layout", handles.msbField);
        assertNotNull(handles.metaField);

        //setChunkModified resolved through the isModified field
        assertNull(handles.setChunkModified);
        assertNotNull(handles.isModifiedField);
        assertEquals("isModified", handles.isModifiedField.getName());
    }

    @Test
    public void probeFailsCleanlyOnNull() {
        try {
            NmsProbe.probe(null);
            fail("expected ProbeException");
        } catch (ProbeException ex) {
            assertNotNull(ex.getMessage());
        }
    }

    @Test
    public void probeFailsCleanlyWithoutGetHandle() {
        try {
            NmsProbe.probe(Object.class);
            fail("expected ProbeException");
        } catch (ProbeException ex) {
            assertTrue(ex.getMessage().contains("getHandle"));
        }
    }

    @Test
    public void probeFailsCleanlyWithoutMetadataArray() {
        try {
            NmsProbe.probe(FakeCraftWorldNoMeta.class);
            fail("expected ProbeException");
        } catch (ProbeException ex) {
            assertTrue(ex.getMessage().contains("metadata"));
        }
    }

    @Test
    public void probeFailsCleanlyWithoutSections() {
        try {
            NmsProbe.probe(FakeCraftWorldNoSections.class);
            fail("expected ProbeException");
        } catch (ProbeException ex) {
            assertTrue(ex.getMessage().contains("getBlockStorageArray"));
        }
    }
}
