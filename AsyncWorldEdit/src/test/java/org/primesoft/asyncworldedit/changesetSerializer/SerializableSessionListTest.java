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
package org.primesoft.asyncworldedit.changesetSerializer;

import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.MaxChangedBlocksException;
import com.sk89q.worldedit.StubEditSession;
import com.sk89q.worldedit.Vector;
import com.sk89q.worldedit.WorldEditException;
import com.sk89q.worldedit.blocks.BaseBlock;
import com.sk89q.worldedit.event.extent.EditSessionEvent;
import com.sk89q.worldedit.history.change.Change;
import com.sk89q.worldedit.history.changeset.BlockOptimizedHistory;
import com.sk89q.worldedit.history.changeset.ChangeSet;
import com.sk89q.worldedit.patterns.Pattern;
import com.sk89q.worldedit.util.eventbus.EventBus;
import java.io.File;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import static org.junit.Assert.*;
import org.junit.After;
import org.junit.Test;
import org.primesoft.asyncworldedit.api.IWorld;
import org.primesoft.asyncworldedit.api.blockPlacer.IBlockPlacer;
import org.primesoft.asyncworldedit.api.blockPlacer.entries.IJobEntry;
import org.primesoft.asyncworldedit.api.playerManager.IPlayerEntry;
import org.primesoft.asyncworldedit.api.worldedit.IThreadSafeEditSession;
import org.primesoft.asyncworldedit.chunkbatch.undo.ColumnarSpoolRegistry;
import org.primesoft.asyncworldedit.chunkbatch.undo.ColumnarUndoLog;
import org.primesoft.asyncworldedit.worldedit.history.changeset.ColumnarUndoSink;
import org.primesoft.asyncworldedit.worldedit.history.changeset.CompositeChangeSet;

/**
 * Lifecycle regression tests for the serializable session history list in
 * columnar undo mode: a session leaving the history (logout removal, undo
 * depth eviction, clear) must close its composite change set, which
 * deletes the columnar spool files - the routine disk leak guard.
 *
 * @author KAMKEEL
 */
public class SerializableSessionListTest {

    /**
     * A history list entry whose root change set is the given composite:
     * extends the real EditSession (the list stores EditSession) and
     * implements just enough of IThreadSafeEditSession for the
     * assign/release lifecycle
     */
    private static final class FakeSession extends StubEditSession
            implements IThreadSafeEditSession {

        private final ChangeSet m_root;

        FakeSession(ChangeSet root) {
            m_root = root;
        }

        @Override
        public ChangeSet getRootChangeSet() {
            return m_root;
        }

        @Override
        public void addAsync(IJobEntry job) {
        }

        @Override
        public boolean checkAsync(String operationName) {
            return false;
        }

        @Override
        public IBlockPlacer getBlockPlacer() {
            return null;
        }

        @Override
        public IWorld getCBWorld() {
            return null;
        }

        @Override
        public EditSessionEvent getEditSessionEvent() {
            return null;
        }

        @Override
        public EventBus getEventBus() {
            return null;
        }

        @Override
        public Object getMutex() {
            return this;
        }

        @Override
        public IPlayerEntry getPlayer() {
            return null;
        }

        @Override
        public boolean isAsyncForced() {
            return false;
        }

        @Override
        public void removeAsync(IJobEntry job) {
        }

        @Override
        public void resetAsync() {
        }

        @Override
        public void setAsyncForced(boolean value) {
        }

        @Override
        public void setAsyncForcedDisable(boolean value) {
        }

        @Override
        public boolean setBlock(int jobId, Vector position, BaseBlock block,
                EditSession.Stage stage) throws WorldEditException {
            return false;
        }

        @Override
        public boolean setBlock(Vector pt, Pattern pat, int jobId)
                throws MaxChangedBlocksException {
            return false;
        }

        @Override
        public boolean setBlock(Vector pt, BaseBlock block, int jobId)
                throws MaxChangedBlocksException {
            return false;
        }

        @Override
        public boolean setBlockIfAir(Vector pt, BaseBlock block, int jobId)
                throws MaxChangedBlocksException {
            return false;
        }

        @Override
        public Iterator<Change> doUndo() {
            return null;
        }

        @Override
        public Iterator<Change> doRedo() {
            return null;
        }

        @Override
        public void doCustomAction(Change change, boolean isDemanding)
                throws WorldEditException {
        }
    }

    private final List<File> m_spools = new ArrayList<File>();

    @After
    public void cleanup() {
        for (File spool : m_spools) {
            if (spool.exists()) {
                spool.delete();
            }
        }
    }

    /**
     * A session whose composite holds one spooled columnar log (the spool
     * file exists on disk and reads live)
     */
    private FakeSession sessionWithSpool() throws Exception {
        final File spool = File.createTempFile("awe-session-list-test", ".bin");
        spool.delete();
        m_spools.add(spool);

        final ColumnarUndoSink sink = new ColumnarUndoSink(
                new ColumnarUndoLog(spool, 0), null);
        sink.beginSection(0, 0, 0, 0, 0);
        sink.capture(0, 1, 0, 2, 0);
        sink.endSection();
        assertTrue("the capture must have spilled to the spool file",
                spool.exists());
        assertTrue(ColumnarSpoolRegistry.isLive(spool));

        final CompositeChangeSet composite
                = new CompositeChangeSet(new BlockOptimizedHistory());
        composite.attach(sink);
        return new FakeSession(composite);
    }

    private File spoolOf(int index) {
        return m_spools.get(index);
    }

    @Test
    public void removeClosesTheCompositeAndDeletesTheSpool() throws Exception {
        //The logout path: AsyncSessionManager.remove -> cleanupSession ->
        //history clear/remove -> releaseSession
        final SerializableSessionList list = new SerializableSessionList();
        final FakeSession session = sessionWithSpool();
        list.add(session);
        assertTrue(spoolOf(0).exists());

        list.remove(session);

        assertFalse("the session left the history: its spool must die",
                spoolOf(0).exists());
        assertFalse(ColumnarSpoolRegistry.isLive(spoolOf(0)));
        assertEquals(0, ((CompositeChangeSet) session.getRootChangeSet())
                .getAttachedCount());
    }

    @Test
    public void undoDepthEvictionReleasesTheEvictedSessionOnly() throws Exception {
        //LocalSession.remember evicts the oldest entry with remove(0) when
        //the history exceeds MAX_HISTORY_SIZE
        final SerializableSessionList list = new SerializableSessionList();
        final FakeSession oldest = sessionWithSpool();
        final FakeSession newer = sessionWithSpool();
        list.add(oldest);
        list.add(newer);

        list.remove(0);

        assertFalse("the evicted session's spool must die", spoolOf(0).exists());
        assertTrue("the retained session's spool must survive", spoolOf(1).exists());
        assertTrue(ColumnarSpoolRegistry.isLive(spoolOf(1)));
    }

    @Test
    public void setReleasesTheReplacedSessionAndKeepsTheElement() throws Exception {
        //set(index, element) used to release the old session and then
        //re-assign the OLD session instead of the element - since
        //releaseSession deletes the columnar spools, the replaced
        //session's release must hit exactly the old one
        final SerializableSessionList list = new SerializableSessionList();
        final FakeSession replaced = sessionWithSpool();
        final FakeSession element = sessionWithSpool();
        list.add(replaced);

        final EditSession previous = list.set(0, element);

        assertSame(replaced, previous);
        assertSame(element, list.get(0));
        assertFalse("the replaced session's spool must die", spoolOf(0).exists());
        assertTrue("the incoming session's spool must survive",
                spoolOf(1).exists());
        assertTrue(ColumnarSpoolRegistry.isLive(spoolOf(1)));
    }

    @Test
    public void clearReleasesEverySession() throws Exception {
        //The AsyncSessionManager.cleanupSession path (logout, session
        //manager clear)
        final SerializableSessionList list = new SerializableSessionList();
        list.add(sessionWithSpool());
        list.add(sessionWithSpool());

        list.clear();

        assertTrue(list.isEmpty());
        assertFalse(spoolOf(0).exists());
        assertFalse(spoolOf(1).exists());
    }
}
