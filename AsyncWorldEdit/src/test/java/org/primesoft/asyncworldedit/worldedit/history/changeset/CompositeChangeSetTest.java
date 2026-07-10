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
package org.primesoft.asyncworldedit.worldedit.history.changeset;

import com.sk89q.worldedit.BlockVector;
import com.sk89q.worldedit.blocks.BaseBlock;
import com.sk89q.worldedit.history.change.BlockChange;
import com.sk89q.worldedit.history.change.Change;
import com.sk89q.worldedit.history.changeset.ChangeSet;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import static org.junit.Assert.*;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.primesoft.asyncworldedit.api.utils.IDisposable;
import org.primesoft.asyncworldedit.chunkbatch.undo.ColumnarUndoLog;
import org.primesoft.asyncworldedit.chunkbatch.undo.ColumnarUndoRegistry;

/**
 * Exact-value tests for the composite change set: backward = all columnar
 * changes (logs in reverse attach order, segments/runs/slots reversed)
 * then the object changes backward, forward is the exact mirror, changes
 * materialize lazily, the iterator carries the thread safe and disposable
 * markers, dispose keeps the spool files (redo) and close deletes them.
 *
 * @author KAMKEEL
 */
public class CompositeChangeSetTest {

    /**
     * Recording object change set: insertion ordered list, counts the
     * iterator requests (laziness assertion) and hands out disposable
     * iterators
     */
    private static final class FakeObjectChangeSet implements ChangeSet {

        final List<Change> changes = new ArrayList<Change>();
        int backwardRequests;
        int forwardRequests;
        int disposed;

        private final class TrackingIterator implements Iterator<Change>, IDisposable {

            private final Iterator<Change> m_inner;

            TrackingIterator(Iterator<Change> inner) {
                m_inner = inner;
            }

            @Override
            public boolean hasNext() {
                return m_inner.hasNext();
            }

            @Override
            public Change next() {
                return m_inner.next();
            }

            @Override
            public void remove() {
                throw new UnsupportedOperationException();
            }

            @Override
            public void dispose() {
                disposed++;
            }
        }

        @Override
        public void add(Change change) {
            changes.add(change);
        }

        @Override
        public Iterator<Change> backwardIterator() {
            backwardRequests++;
            final List<Change> reversed = new ArrayList<Change>(changes);
            Collections.reverse(reversed);
            return new TrackingIterator(reversed.iterator());
        }

        @Override
        public Iterator<Change> forwardIterator() {
            forwardRequests++;
            return new TrackingIterator(new ArrayList<Change>(changes).iterator());
        }

        @Override
        public int size() {
            return changes.size();
        }
    }

    private final List<File> m_spools = new ArrayList<File>();
    private final List<ColumnarUndoSink> m_sinks = new ArrayList<ColumnarUndoSink>();

    private FakeObjectChangeSet m_object;
    private CompositeChangeSet m_composite;

    @Before
    public void setUp() {
        m_object = new FakeObjectChangeSet();
        m_composite = new CompositeChangeSet(m_object);
    }

    @After
    public void cleanup() {
        for (ColumnarUndoSink sink : m_sinks) {
            sink.close();
        }
        for (File spool : m_spools) {
            if (spool.exists()) {
                spool.delete();
            }
        }
    }

    private ColumnarUndoSink sink(long thresholdBytes) throws Exception {
        final File spool = File.createTempFile("awe-composite-test", ".bin");
        spool.delete();
        m_spools.add(spool);

        final ColumnarUndoSink sink = new ColumnarUndoSink(
                new ColumnarUndoLog(spool, thresholdBytes), null);
        m_sinks.add(sink);
        return sink;
    }

    private static BlockChange objectChange(int x, int y, int z,
            int oldId, int oldData, int newId, int newData) {
        return new BlockChange(new BlockVector(x, y, z),
                new BaseBlock(oldId, oldData), new BaseBlock(newId, newData));
    }

    /**
     * "x,y,z:oldId:oldData&gt;newId:newData" of a materialized change
     */
    private static String describe(Change change) {
        assertTrue("expected a BlockChange but got "
                + change.getClass().getName(), change instanceof BlockChange);
        final BlockChange bc = (BlockChange) change;
        final BaseBlock previous = bc.getPrevious();
        final BaseBlock current = bc.getCurrent();
        return bc.getPosition().getBlockX()
                + "," + bc.getPosition().getBlockY()
                + "," + bc.getPosition().getBlockZ()
                + ":" + previous.getType() + ":" + previous.getData()
                + ">" + current.getType() + ":" + current.getData();
    }

    private static List<String> drain(Iterator<Change> iterator) {
        final List<String> result = new ArrayList<String>();
        while (iterator.hasNext()) {
            result.add(describe(iterator.next()));
        }
        return result;
    }

    /**
     * Two object changes, log A (attached first, chunk (0,0) section 0
     * with a two slot run) and log B (chunk (1,0) section 2, one slot)
     */
    private void buildTwoLogsAndTwoObjectChanges() throws Exception {
        m_composite.add(objectChange(100, 1, 100, 50, 0, 51, 1));
        m_composite.add(objectChange(101, 1, 100, 50, 0, 52, 2));

        final ColumnarUndoSink a = sink(Long.MAX_VALUE);
        a.beginSection(0, 0, 0, 0, 10);
        a.capture(0, 1, 0, 2, 0);
        a.capture(1, 1, 0, 2, 0);
        a.endSection();
        m_composite.attach(a);

        final ColumnarUndoSink b = sink(Long.MAX_VALUE);
        b.beginSection(1, 0, 2, 11, 20);
        b.capture(5, 3, 1, 4, 2);
        b.endSection();
        m_composite.attach(b);
    }

    @Test
    public void backwardIsColumnarInReverseAttachOrderThenObjectBackward() throws Exception {
        buildTwoLogsAndTwoObjectChanges();

        final Iterator<Change> it = m_composite.backwardIterator();
        assertTrue("the composite iterator must pass the thread safe wrapper"
                + " without an eager copy",
                it instanceof ThreadSafeChangeSet.IThreadSafeIterator);
        assertTrue("UndoProcessor.resume disposes the iterator",
                it instanceof IDisposable);

        final List<String> changes = drain(it);
        assertEquals(5, changes.size());
        //Log B first (reverse attach order): chunk (1,0) section 2 slot 5
        //is world (21, 32, 0)
        assertEquals("21,32,0:3:1>4:2", changes.get(0));
        //Log A: run slots in reverse (slot 1, then slot 0)
        assertEquals("1,0,0:1:0>2:0", changes.get(1));
        assertEquals("0,0,0:1:0>2:0", changes.get(2));
        //Object changes backward (reverse insertion order)
        assertEquals("101,1,100:50:0>52:2", changes.get(3));
        assertEquals("100,1,100:50:0>51:1", changes.get(4));
    }

    @Test
    public void forwardIsTheExactMirror() throws Exception {
        buildTwoLogsAndTwoObjectChanges();

        final List<String> changes = drain(m_composite.forwardIterator());
        assertEquals(5, changes.size());
        assertEquals("100,1,100:50:0>51:1", changes.get(0));
        assertEquals("101,1,100:50:0>52:2", changes.get(1));
        assertEquals("0,0,0:1:0>2:0", changes.get(2));
        assertEquals("1,0,0:1:0>2:0", changes.get(3));
        assertEquals("21,32,0:3:1>4:2", changes.get(4));
    }

    @Test
    public void backwardCreatesTheObjectIteratorOnlyAfterTheColumnarPhase() throws Exception {
        buildTwoLogsAndTwoObjectChanges();

        final Iterator<Change> it = m_composite.backwardIterator();
        //The three columnar changes come first; the object change set must
        //not have been touched yet (its file iterator would open a stream
        //and message the player)
        for (int i = 0; i < 3; i++) {
            assertNotNull(it.next());
        }
        assertEquals(0, m_object.backwardRequests);

        assertNotNull(it.next());
        assertEquals(1, m_object.backwardRequests);
    }

    @Test
    public void disposeReleasesTheObjectIteratorButKeepsTheSpool() throws Exception {
        //Threshold 0: the single segment spills, the spool file exists
        final ColumnarUndoSink spooled = sink(0);
        spooled.beginSection(0, 0, 0, 0, 1);
        spooled.capture(0, 1, 0, 2, 0);
        spooled.endSection();
        m_composite.attach(spooled);
        m_composite.add(objectChange(5, 5, 5, 7, 0, 8, 0));

        final File spool = m_spools.get(0);
        assertTrue(spool.exists());

        final Iterator<Change> it = m_composite.backwardIterator();
        assertEquals(2, drain(it).size());
        ((IDisposable) it).dispose();

        //Dispose = end of one undo replay; a redo still needs the spool
        assertTrue(spool.exists());
        assertEquals(1, m_object.disposed);

        //Redo replays from the same spool
        assertEquals(2, drain(m_composite.forwardIterator()).size());

        //Session leaves the history: now the spool dies
        m_composite.close();
        assertFalse(spool.exists());
        assertEquals(0, m_composite.getAttachedCount());
    }

    @Test
    public void closeUnregistersTheAttachedSinks() throws Exception {
        final UUID player = UUID.randomUUID();
        final ColumnarUndoSink sink = sink(Long.MAX_VALUE);
        assertTrue(ColumnarUndoRegistry.register(player, 77, sink));
        m_composite.attach(sink);

        assertSame(sink, ColumnarUndoRegistry.get(player, 77));
        m_composite.close();
        assertNull("close is the safety net for jobs whose buffer never"
                + " fired the prune hook", ColumnarUndoRegistry.get(player, 77));
    }

    @Test
    public void backwardSkipsRedoOnlyRewriteSegmentsForwardReplaysThem() throws Exception {
        //A cross-flush rewrite (//move overlap over a window eviction):
        //slot 7 captured 1>20 in flush one, re-flushed as 20>30 later
        final ColumnarUndoSink sink = sink(Long.MAX_VALUE);
        sink.beginSection(0, 0, 0, 0, 10);
        sink.capture(7, 1, 0, 20, 0);
        sink.endSection();
        sink.beginSection(0, 0, 0, 11, 20);
        sink.capture(7, 20, 0, 30, 0);
        sink.capture(8, 1, 0, 30, 0);
        sink.endSection();
        m_composite.attach(sink);

        //Undo: the rewrite segment must NOT restore the intermediate 20
        final List<String> backward = drain(m_composite.backwardIterator());
        assertEquals(2, backward.size());
        assertEquals("8,0,0:1:0>30:0", backward.get(0));
        assertEquals("7,0,0:1:0>20:0", backward.get(1));

        //Redo: slot 7 ends at the FINAL value 30 (the redo-only rewrite
        //segment replays after the normal segment)
        final List<String> forward = drain(m_composite.forwardIterator());
        assertEquals(3, forward.size());
        assertEquals("7,0,0:1:0>20:0", forward.get(0));
        assertEquals("8,0,0:1:0>30:0", forward.get(1));
        assertEquals("7,0,0:20:0>30:0", forward.get(2));
    }

    @Test
    public void backwardSkipsRedoOnlySegmentsFromTheSpoolFile() throws Exception {
        //Threshold 0: both segments spill; the skip must work against the
        //spooled segment directory too
        final ColumnarUndoSink sink = sink(0);
        sink.beginSection(0, 0, 0, 0, 10);
        sink.capture(7, 1, 0, 20, 0);
        sink.endSection();
        sink.beginSection(0, 0, 0, 11, 20);
        sink.capture(7, 20, 0, 30, 0);
        sink.endSection();
        m_composite.attach(sink);

        final List<String> backward = drain(m_composite.backwardIterator());
        assertEquals(1, backward.size());
        assertEquals("7,0,0:1:0>20:0", backward.get(0));

        final List<String> forward = drain(m_composite.forwardIterator());
        assertEquals(2, forward.size());
        assertEquals("7,0,0:20:0>30:0", forward.get(1));
    }

    @Test
    public void sizeIsObjectSizePlusCaptureCounts() throws Exception {
        buildTwoLogsAndTwoObjectChanges();
        assertEquals(5, m_composite.size());
    }

    @Test
    public void addDelegatesToTheObjectChangeSet() {
        m_composite.add(objectChange(1, 2, 3, 0, 0, 1, 0));
        assertEquals(1, m_object.changes.size());
    }

    @Test
    public void emptyCompositeIteratesNothing() {
        assertFalse(m_composite.backwardIterator().hasNext());
        assertFalse(m_composite.forwardIterator().hasNext());
        assertEquals(0, m_composite.size());
    }
}
