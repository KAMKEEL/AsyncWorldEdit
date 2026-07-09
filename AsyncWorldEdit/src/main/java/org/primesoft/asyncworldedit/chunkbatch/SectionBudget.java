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

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Global memory cap on allocated pending section buffers, shared between
 * the per run chunk batch window ({@link ChunkBatchWriter}) and the per
 * job block buffers ({@link JobBufferRegistry}).
 *
 * A pending section buffer costs a fixed amount of memory (the slot and
 * sequence arrays, 32 KB), so bounding the number of live buffers bounds
 * the total batch memory (the default of 1024 sections caps it at 32 MB).
 * Writers that would need a new section buffer beyond the cap must fall
 * back to the classic per block path, which naturally throttles admission
 * against the queue limits and the tick budget.
 *
 * Thread safe: sections are acquired on async producer threads and
 * released on the main thread during flushes.
 *
 * @author KAMKEEL
 */
public final class SectionBudget {

    /**
     * Default upper bound of allocated pending section buffers
     */
    public static final int DEFAULT_MAX_SECTIONS = 1024;

    /**
     * The budget shared by all production writers
     */
    private static final SectionBudget s_shared = new SectionBudget(DEFAULT_MAX_SECTIONS);

    /**
     * The budget shared by the chunk batch window and the job buffers
     */
    public static SectionBudget getShared() {
        return s_shared;
    }

    /**
     * Maximum number of live section buffers
     */
    private volatile int m_maxSections;

    /**
     * Number of currently allocated section buffers
     */
    private final AtomicInteger m_used = new AtomicInteger();

    public SectionBudget(int maxSections) {
        m_maxSections = Math.max(1, maxSections);
    }

    /**
     * Try to reserve one section buffer.
     *
     * @return true when the buffer fits under the cap (the caller now owns
     * one reservation), false when the cap is reached (the caller must
     * fall back to the classic path)
     */
    public boolean tryAcquire() {
        for (;;) {
            int used = m_used.get();
            if (used >= m_maxSections) {
                return false;
            }
            if (m_used.compareAndSet(used, used + 1)) {
                return true;
            }
        }
    }

    /**
     * Release section buffer reservations (the buffers were flushed or
     * discarded)
     *
     * @param count number of reservations to release
     */
    public void release(int count) {
        if (count <= 0) {
            return;
        }

        for (;;) {
            int used = m_used.get();
            int next = used - count;
            if (next < 0) {
                next = 0;
            }
            if (m_used.compareAndSet(used, next)) {
                return;
            }
        }
    }

    /**
     * Number of currently allocated section buffers
     */
    public int getUsed() {
        return m_used.get();
    }

    /**
     * The section buffer cap
     */
    public int getMaxSections() {
        return m_maxSections;
    }

    /**
     * Change the cap (test seam)
     */
    void setMaxSections(int maxSections) {
        m_maxSections = Math.max(1, maxSections);
    }
}
