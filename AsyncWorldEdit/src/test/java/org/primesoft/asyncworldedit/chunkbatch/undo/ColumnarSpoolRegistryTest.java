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
package org.primesoft.asyncworldedit.chunkbatch.undo;

import java.io.File;
import java.nio.file.Files;
import static org.junit.Assert.*;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests for the columnar spool liveness registry and the orphan sweep:
 * the name matcher, log construction/close driving liveness, and the
 * sweep deleting exactly the dead columnar files of an undo tree while
 * leaving ts undo files and live spools alone.
 *
 * @author KAMKEEL
 */
public class ColumnarSpoolRegistryTest {

    private File m_root;
    private ColumnarUndoLog m_liveLog;

    @Before
    public void createUndoTree() throws Exception {
        m_root = Files.createTempDirectory("awe-undo-sweep").toFile();
    }

    @After
    public void cleanup() {
        if (m_liveLog != null) {
            m_liveLog.close();
            m_liveLog = null;
        }
        delete(m_root);
    }

    private static void delete(File file) {
        if (file == null) {
            return;
        }
        final File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                delete(child);
            }
        }
        file.delete();
    }

    private File playerDir(String name) {
        final File dir = new File(m_root, name);
        assertTrue(dir.mkdirs());
        return dir;
    }

    private static File createFile(File dir, String name) throws Exception {
        final File file = new File(dir, name);
        assertTrue(file.createNewFile());
        return file;
    }

    @Test
    public void matcherAcceptsOnlyColumnarSpoolNames() {
        assertTrue(ColumnarSpoolRegistry.isColumnarSpool(
                new File("columnar.3.1720000000000.bin")));
        assertFalse("ts undo files belong to the keepUndoFileFor cleanup",
                ColumnarSpoolRegistry.isColumnarSpool(new File("ts.1a2b3c.undo")));
        assertFalse(ColumnarSpoolRegistry.isColumnarSpool(new File("columnar.bin")));
        assertFalse(ColumnarSpoolRegistry.isColumnarSpool(new File("columnar.3.txt")));
        assertFalse(ColumnarSpoolRegistry.isColumnarSpool(null));
    }

    @Test
    public void logConstructionMarksLiveAndCloseMarksDead() throws Exception {
        final File spool = new File(playerDir("uuid-a"), "columnar.1.111.bin");

        assertFalse(ColumnarSpoolRegistry.isLive(spool));

        //Liveness starts at construction, BEFORE the file exists (it is
        //created lazily on the first spill and must never be swept in
        //between)
        m_liveLog = new ColumnarUndoLog(spool, 0);
        assertTrue(ColumnarSpoolRegistry.isLive(spool));

        m_liveLog.close();
        m_liveLog = null;
        assertFalse(ColumnarSpoolRegistry.isLive(spool));
    }

    @Test
    public void sweepDeletesOrphansAndKeepsLiveSpoolsAndTsFiles() throws Exception {
        final File dirA = playerDir("uuid-a");
        final File dirB = playerDir("uuid-b");

        //An orphan (no live log), a live spool and a ts undo file
        final File orphan = createFile(dirA, "columnar.1.111.bin");
        final File tsFile = createFile(dirA, "ts.1a2b3c.undo");

        final File liveSpool = new File(dirB, "columnar.2.222.bin");
        m_liveLog = new ColumnarUndoLog(liveSpool, 0);
        m_liveLog.beginSection(0, 0, 0, 0, 0);
        m_liveLog.capture(0, 1, 0, 2, 0);
        m_liveLog.endSection();
        assertTrue(liveSpool.exists());

        assertEquals(1, ColumnarSpoolRegistry.sweepOrphans(m_root));

        assertFalse("the orphan must be deleted", orphan.exists());
        assertTrue("the live spool must survive", liveSpool.exists());
        assertTrue("ts files belong to the keepUndoFileFor cleanup",
                tsFile.exists());
    }

    @Test
    public void startupSweepDeletesEverythingBecauseNothingIsLive() throws Exception {
        //On plugin enable no session exists: every columnar file in the
        //tree is a crash/shutdown leftover
        final File dirA = playerDir("uuid-a");
        final File dirB = playerDir("uuid-b");
        final File one = createFile(dirA, "columnar.1.111.bin");
        final File two = createFile(dirB, "columnar.7.777.bin");

        assertEquals(2, ColumnarSpoolRegistry.sweepOrphans(m_root));
        assertFalse(one.exists());
        assertFalse(two.exists());
    }

    @Test
    public void sweepOfAMissingRootIsANoOp() throws Exception {
        assertEquals(0, ColumnarSpoolRegistry.sweepOrphans(
                new File(m_root, "does-not-exist")));
        assertEquals(0, ColumnarSpoolRegistry.sweepOrphans(null));
    }
}
