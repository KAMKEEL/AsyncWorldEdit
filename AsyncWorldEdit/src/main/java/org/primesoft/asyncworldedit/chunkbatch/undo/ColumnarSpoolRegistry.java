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
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Liveness registry of the columnar undo spool files plus the orphan
 * sweep that deletes dead ones from the undo tree.
 *
 * A spool file is LIVE while the {@link ColumnarUndoLog} that owns it is
 * both registered and strongly reachable (the log registers on
 * construction and unregisters on close; the registry itself only holds a
 * weak reference, so a session dropped without cleanup - WorldEdit's
 * session expiration timer bypassing the AWE session manager, a crash of
 * the cleanup path - eventually reads as dead once the log is collected).
 * Everything else matching the columnar spool name pattern inside the
 * undo tree is an orphan: unlike AWE's own ts undo files there is no
 * on-disk directory to reload a columnar log from, so an orphan can never
 * be referenced again and is deleted regardless of keepUndoFileFor.
 *
 * The sweep runs on plugin enable (no session exists yet, so EVERY
 * columnar file is an orphan - crash and shutdown leftovers die here,
 * mirroring how the startup undo cleanup handles the ts files) and again
 * with every Cron undo cleanup pass (runtime leftovers of sessions that
 * never went through releaseSession).
 *
 * Pure logic: java.io/nio only, no Bukkit/WorldEdit imports (chunkbatch
 * conventions); Cron wires it to the schedule.
 *
 * @author KAMKEEL
 */
public final class ColumnarSpoolRegistry {

    /**
     * File name prefix of a columnar undo spool file (see
     * ExtendedChangeSetExtent.registerJobLog: columnar.&lt;job&gt;.&lt;millis&gt;.bin)
     */
    private static final String PREFIX = "columnar.";

    /**
     * File name suffix of a columnar undo spool file
     */
    private static final String SUFFIX = ".bin";

    /**
     * The live spool files: absolute path -> weak reference to the owning
     * log. Weak so an abandoned session (never closed) cannot pin its
     * spool files forever - once the log is collected the file is dead.
     */
    private static final ConcurrentMap<String, WeakReference<ColumnarUndoLog>> s_live
            = new ConcurrentHashMap<String, WeakReference<ColumnarUndoLog>>();

    private ColumnarSpoolRegistry() {
    }

    private static String keyOf(File spool) {
        return spool.getAbsolutePath();
    }

    /**
     * Mark a spool file live (called by the owning log's constructor -
     * BEFORE the file exists; the file is created lazily on the first
     * spill and must never be swept in between)
     */
    static void register(File spool, ColumnarUndoLog owner) {
        if (spool == null || owner == null) {
            return;
        }
        s_live.put(keyOf(spool), new WeakReference<ColumnarUndoLog>(owner));
    }

    /**
     * Drop the liveness of a spool file (called by the owning log's close,
     * which also deletes the file)
     */
    static void unregister(File spool) {
        if (spool == null) {
            return;
        }
        s_live.remove(keyOf(spool));
    }

    /**
     * True when the file name matches the columnar spool pattern
     */
    public static boolean isColumnarSpool(File file) {
        if (file == null) {
            return false;
        }
        final String name = file.getName();
        return name.length() > PREFIX.length() + SUFFIX.length()
                && name.startsWith(PREFIX) && name.endsWith(SUFFIX);
    }

    /**
     * True while the owning log is registered and still reachable.
     * Cleared weak references are purged as they are seen.
     */
    public static boolean isLive(File file) {
        final String key = keyOf(file);
        final WeakReference<ColumnarUndoLog> ref = s_live.get(key);
        if (ref == null) {
            return false;
        }
        if (ref.get() == null) {
            //The owning log was collected without a close (an abandoned
            //session): the file is dead
            s_live.remove(key, ref);
            return false;
        }
        return true;
    }

    /**
     * Delete every orphaned (non live) columnar spool file inside the
     * undo tree. Same walk shape as the ts file cleanup
     * (SerializerManager.getUndoFiles): undo root + one player folder
     * level.
     *
     * @param undoRoot the undo folder (plugins/AsyncWorldEdit/undo)
     * @return number of deleted files
     * @throws IOException when the tree cannot be walked
     */
    public static int sweepOrphans(File undoRoot) throws IOException {
        if (undoRoot == null || !undoRoot.isDirectory()) {
            return 0;
        }

        int deleted = 0;
        final Iterator<Path> it = Files.walk(undoRoot.toPath(), 2,
                FileVisitOption.FOLLOW_LINKS).iterator();
        while (it.hasNext()) {
            final File file = it.next().toFile();
            if (!file.isFile() || !isColumnarSpool(file) || isLive(file)) {
                continue;
            }
            if (file.delete()) {
                deleted++;
            }
        }
        return deleted;
    }

    /**
     * Number of registered live spool files (telemetry + tests); purges
     * cleared references
     */
    public static int size() {
        for (Iterator<Map.Entry<String, WeakReference<ColumnarUndoLog>>> it
                = s_live.entrySet().iterator(); it.hasNext();) {
            if (it.next().getValue().get() == null) {
                it.remove();
            }
        }
        return s_live.size();
    }
}
