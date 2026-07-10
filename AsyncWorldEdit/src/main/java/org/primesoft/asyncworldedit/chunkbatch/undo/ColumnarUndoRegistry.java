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

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Live capture sinks of the buffered jobs, keyed by (player uuid, job id)
 * and BOUND to the identity of the owning session's composite change set.
 *
 * A session's suppression tee registers its sink the first time it
 * suppresses the object recording of a job's write - BEFORE that write can
 * reach the job buffer - so the main thread flush always finds the sink of
 * every capture-eligible buffered block. Loose writes (job id -1, e.g. the
 * //undo replay) are never registered and therefore never captured.
 *
 * Ownership: job ids are reused (BlockPlacerPlayer.getNextJobId is
 * max(live)+1), so a registration that outlived its job - a job that
 * registered at first suppression but never created a buffer, leaving the
 * prune hook nothing to fire on - would otherwise be found by the NEXT
 * job with the same id and silently swallow the new edit's undo into the
 * dead session's log. Every entry therefore carries its owner (the
 * session's composite change set); {@link #resolveOwned} only trusts a
 * hit with the caller's owner and evicts + seals a foreign (stale) one,
 * and the block placer proactively unregisters a removed job's sink when
 * no buffer exists for it.
 *
 * Entries are removed when the job's buffer is pruned (job done, all
 * flushed or discarded - the sink is sealed then), when the job is
 * removed without ever having buffered, and, as a safety net, when the
 * owning session's change set is closed.
 *
 * @author KAMKEEL
 */
public final class ColumnarUndoRegistry {

    /**
     * Composite key: player uuid + job id
     */
    private static final class Key {

        final UUID uuid;
        final int jobId;

        Key(UUID uuid, int jobId) {
            this.uuid = uuid;
            this.jobId = jobId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Key)) {
                return false;
            }
            Key other = (Key) o;
            return jobId == other.jobId
                    && (uuid == null ? other.uuid == null : uuid.equals(other.uuid));
        }

        @Override
        public int hashCode() {
            return 31 * (uuid == null ? 0 : uuid.hashCode()) + jobId;
        }
    }

    /**
     * A registered sink and the identity of its owning session's
     * composite change set
     */
    private static final class Entry {

        final ICaptureSink sink;
        final Object owner;

        Entry(ICaptureSink sink, Object owner) {
            this.sink = sink;
            this.owner = owner;
        }
    }

    private static final ConcurrentMap<Key, Entry> s_sinks
            = new ConcurrentHashMap<Key, Entry>();

    private ColumnarUndoRegistry() {
    }

    /**
     * Register the capture sink of a job for an owner. First registration
     * wins (all of a job's writes belong to one session).
     *
     * @param jobId the job id, must be &gt;= 0 (loose writes are never
     * captured)
     * @param owner the owning session's composite change set identity
     * @return true when this registration won; false when the job already
     * has a sink (or the arguments are invalid) - the caller should use
     * {@link #resolveOwned} and drop its own instance
     */
    public static boolean register(UUID player, int jobId, ICaptureSink sink,
            Object owner) {
        if (jobId < 0 || sink == null) {
            return false;
        }
        return s_sinks.putIfAbsent(new Key(player, jobId),
                new Entry(sink, owner)) == null;
    }

    /**
     * The capture sink of a job, null when the job records undo through
     * the object change set (or not at all). The flush path resolution -
     * ownership was already enforced at registration time by
     * {@link #resolveOwned}.
     */
    public static ICaptureSink get(UUID player, int jobId) {
        if (jobId < 0) {
            return null;
        }
        final Entry entry = s_sinks.get(new Key(player, jobId));
        return entry == null ? null : entry.sink;
    }

    /**
     * Resolve the sink of a job for a specific owner (the suppression
     * seam). A hit registered by ANOTHER owner is a stale leftover of a
     * dead job whose id was reused - ids are unique among a player's live
     * jobs, so two owners can never legitimately share one - and is
     * evicted and sealed so it can neither swallow the new job's captures
     * nor accept any of its own.
     *
     * @return the caller's sink, or null when the caller must register
     * its own
     */
    public static ICaptureSink resolveOwned(UUID player, int jobId, Object owner) {
        if (jobId < 0) {
            return null;
        }
        final Key key = new Key(player, jobId);
        final Entry entry = s_sinks.get(key);
        if (entry == null) {
            return null;
        }
        if (entry.owner == owner) {
            return entry.sink;
        }

        //Stale: evict and seal, then let the caller register its own
        if (s_sinks.remove(key, entry)) {
            entry.sink.jobDone();
        }
        return null;
    }

    /**
     * Drop the sink of a finished job and seal it: no further capture may
     * be accepted in its name (job ids are reused). Called when the job's
     * buffer is pruned or discarded and when a job is removed without
     * ever having buffered.
     */
    public static void unregister(UUID player, int jobId) {
        final Entry entry = s_sinks.remove(new Key(player, jobId));
        if (entry != null) {
            entry.sink.jobDone();
        }
    }

    /**
     * Drop every registration of a closing session's sink WITHOUT sealing
     * (the caller closes the sink right after; safety net for jobs that
     * registered but never buffered - their buffers were never created,
     * so the prune hook never fires)
     */
    public static void unregisterSink(ICaptureSink sink) {
        if (sink == null) {
            return;
        }
        for (Iterator<Map.Entry<Key, Entry>> it
                = s_sinks.entrySet().iterator(); it.hasNext();) {
            if (it.next().getValue().sink == sink) {
                it.remove();
            }
        }
    }

    /**
     * Number of live registrations (telemetry + tests)
     */
    public static int size() {
        return s_sinks.size();
    }
}
