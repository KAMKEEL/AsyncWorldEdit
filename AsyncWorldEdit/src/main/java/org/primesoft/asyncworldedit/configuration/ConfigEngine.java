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
package org.primesoft.asyncworldedit.configuration;

import org.primesoft.asyncworldedit.platform.api.IConfigurationSection;

/**
 * The block placement engine configuration (awe.engine section).
 *
 * @author KAMKEEL
 */
public class ConfigEngine {

    /**
     * Default undo spool threshold in megabytes
     */
    public static final int DEFAULT_UNDO_SPOOL_THRESHOLD_MB = 8;

    /**
     * Default for the region streamed flushing toggle
     */
    public static final boolean DEFAULT_STREAM_ENABLED = true;

    /**
     * Default per job live section window (256 sections = 8 MB per job)
     */
    public static final int DEFAULT_STREAM_WINDOW_SECTIONS = 256;

    /**
     * Default staleness threshold in placer runs (~2s at interval 1)
     */
    public static final int DEFAULT_STREAM_STALE_RUNS = 40;

    private final EngineMode m_mode;

    private final int m_undoSpoolThresholdMb;

    private final boolean m_debug;

    private final boolean m_streamEnabled;

    private final int m_streamWindowSections;

    private final int m_streamStaleRuns;

    /**
     * The active placement engine
     */
    public EngineMode getMode() {
        return m_mode;
    }

    /**
     * True when the buffer-first engine is selected
     */
    public boolean isBuffered() {
        return m_mode == EngineMode.BUFFERED;
    }

    /**
     * Per job undo log memory budget (MB) before it spools to a temp file.
     * Reserved for the packed per job undo log; the current build relies on
     * WorldEdit's operation time change set recording.
     */
    public int getUndoSpoolThresholdMb() {
        return m_undoSpoolThresholdMb;
    }

    /**
     * Verbose engine logging (per placer run + per job completion)
     */
    public boolean isDebug() {
        return m_debug;
    }

    /**
     * Region streamed flushing: flush ready chunks of still-producing jobs
     * during production instead of only at job end
     */
    public boolean isStreamEnabled() {
        return m_streamEnabled;
    }

    /**
     * Per job live section watermark: over this the least-recently-written
     * chunks of the job are streamed out
     */
    public int getStreamWindowSections() {
        return m_streamWindowSections;
    }

    /**
     * Number of placer runs without a write before a chunk is streamed out
     */
    public int getStreamStaleRuns() {
        return m_streamStaleRuns;
    }

    public ConfigEngine(IConfigurationSection engineSection) {
        if (engineSection == null) {
            m_mode = EngineMode.BUFFERED;
            m_undoSpoolThresholdMb = DEFAULT_UNDO_SPOOL_THRESHOLD_MB;
            m_debug = false;
            m_streamEnabled = DEFAULT_STREAM_ENABLED;
            m_streamWindowSections = DEFAULT_STREAM_WINDOW_SECTIONS;
            m_streamStaleRuns = DEFAULT_STREAM_STALE_RUNS;
            return;
        }

        m_mode = EngineMode.parse(engineSection.getString("mode", "buffered"));
        m_undoSpoolThresholdMb = Math.max(1,
                engineSection.getInt("undo-spool-threshold-mb", DEFAULT_UNDO_SPOOL_THRESHOLD_MB));
        m_debug = engineSection.getBoolean("debug", false);
        m_streamEnabled = engineSection.getBoolean("stream.enabled", DEFAULT_STREAM_ENABLED);
        m_streamWindowSections = Math.max(1,
                engineSection.getInt("stream.window-sections", DEFAULT_STREAM_WINDOW_SECTIONS));
        m_streamStaleRuns = Math.max(1,
                engineSection.getInt("stream.stale-runs", DEFAULT_STREAM_STALE_RUNS));
    }
}
