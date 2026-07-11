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

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import static org.junit.Assert.*;
import org.junit.Test;
import org.primesoft.asyncworldedit.platform.api.IConfigurationSection;

/**
 * Exact-value tests for the awe.engine.stream configuration parsing:
 * defaults with a missing section, parsed values and the minimum clamps.
 *
 * @author KAMKEEL
 */
public class ConfigEngineStreamTest {

    /**
     * A fake configuration section answering getBoolean/getInt/getString
     * from a plain map (dotted keys, like the Bukkit sections)
     */
    private static IConfigurationSection section(final Map<String, Object> values) {
        return (IConfigurationSection) Proxy.newProxyInstance(
                ConfigEngineStreamTest.class.getClassLoader(),
                new Class<?>[]{IConfigurationSection.class}, new InvocationHandler() {
            @Override
            public Object invoke(Object o, Method m, Object[] a) {
                final String name = m.getName();
                if ("getBoolean".equals(name) || "getInt".equals(name)
                        || "getLong".equals(name) || "getString".equals(name)) {
                    final Object value = values.get((String) a[0]);
                    return value != null ? value : a[1];
                }
                return null;
            }
        });
    }

    @Test
    public void missingSectionUsesTheStreamDefaults() {
        ConfigEngine config = new ConfigEngine(null);
        assertFalse(config.isStreamEnabled());
        assertEquals(ConfigEngine.DEFAULT_STREAM_WINDOW_SECTIONS,
                config.getStreamWindowSections());
        assertEquals(ConfigEngine.DEFAULT_STREAM_STALE_RUNS,
                config.getStreamStaleRuns());
    }

    @Test
    public void missingStreamKeysUseTheStreamDefaults() {
        ConfigEngine config = new ConfigEngine(section(new HashMap<String, Object>()));
        assertFalse(config.isStreamEnabled());
        assertEquals(256, config.getStreamWindowSections());
        assertEquals(40, config.getStreamStaleRuns());
    }

    @Test
    public void streamKeysParse() {
        Map<String, Object> values = new HashMap<String, Object>();
        values.put("stream.enabled", Boolean.FALSE);
        values.put("stream.window-sections", Integer.valueOf(512));
        values.put("stream.stale-runs", Integer.valueOf(100));

        ConfigEngine config = new ConfigEngine(section(values));
        assertFalse(config.isStreamEnabled());
        assertEquals(512, config.getStreamWindowSections());
        assertEquals(100, config.getStreamStaleRuns());
    }

    @Test
    public void streamKeysClampToOne() {
        Map<String, Object> values = new HashMap<String, Object>();
        values.put("stream.window-sections", Integer.valueOf(0));
        values.put("stream.stale-runs", Integer.valueOf(-5));

        ConfigEngine config = new ConfigEngine(section(values));
        assertEquals(1, config.getStreamWindowSections());
        assertEquals(1, config.getStreamStaleRuns());
    }
}
