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
package org.primesoft.asyncworldedit.commands;

import org.primesoft.asyncworldedit.core.Help;
import org.primesoft.asyncworldedit.api.blockPlacer.IBlockPlacer;
import org.primesoft.asyncworldedit.api.inner.IAsyncWorldEditCore;
import org.primesoft.asyncworldedit.api.playerManager.IPlayerEntry;
import org.primesoft.asyncworldedit.api.taskdispatcher.ITaskDispatcher;
import org.primesoft.asyncworldedit.api.utils.IAction;
import org.primesoft.asyncworldedit.chunkbatch.EngineDebug;
import org.primesoft.asyncworldedit.chunkbatch.EngineStats;
import org.primesoft.asyncworldedit.chunkbatch.JobBufferRegistry;
import org.primesoft.asyncworldedit.chunkbatch.SectionBudget;
import org.primesoft.asyncworldedit.configuration.ConfigProvider;
import org.primesoft.asyncworldedit.permissions.Permission;
import org.primesoft.asyncworldedit.strings.MessageType;

/**
 * Live control of the block placement engine (buffer-first vs classic) and its
 * verbose debug logging, for A/B testing without editing config.yml and
 * restarting.
 *
 * Usage:
 * <ul>
 * <li>{@code /awe engine} - print the active mode, whether buffered was forced
 * to classic by BlocksHub access checking, the debug state and live
 * counters</li>
 * <li>{@code /awe engine buffered} - switch to the buffer-first engine (refused
 * while BlocksHub access checking is enabled)</li>
 * <li>{@code /awe engine classic} - force flush in-flight buffered blocks on the
 * main thread, then switch to classic placement</li>
 * <li>{@code /awe engine debug on|off} - toggle verbose engine logging</li>
 * </ul>
 *
 * @author KAMKEEL
 */
public class EngineCommand {

    public static void Execte(IAsyncWorldEditCore sender, IPlayerEntry player, String[] args) {
        if (args.length < 1 || args.length > 3) {
            Help.ShowHelp(player, Commands.COMMAND_ENGINE);
            return;
        }

        if (!player.isAllowed(Permission.RELOAD_CONFIG)) {
            player.say(MessageType.NO_PERMS.format());
            return;
        }

        if (args.length == 1) {
            showStatus(sender, player);
            return;
        }

        String sub = args[1];
        if (sub.equalsIgnoreCase("buffered")) {
            switchToBuffered(player);
        } else if (sub.equalsIgnoreCase("classic")) {
            switchToClassic(sender, player);
        } else if (sub.equalsIgnoreCase("debug")) {
            toggleDebug(player, args);
        } else {
            Help.ShowHelp(player, Commands.COMMAND_ENGINE);
        }
    }

    private static void showStatus(IAsyncWorldEditCore sender, IPlayerEntry player) {
        player.say(MessageType.CMD_ENGINE_STATUS.format(modeName(ConfigProvider.isBufferedEngine()),
                EngineDebug.isEnabled()
                        ? MessageType.GLOBAL_ON.format()
                        : MessageType.GLOBAL_OFF.format()));

        if (ConfigProvider.isBufferedForcedToClassic()) {
            player.say(MessageType.CMD_ENGINE_STATUS_FORCED.format());
        }

        //Undo mode on the status output so benchmark screenshots are
        //self-documenting (the mode is config-static, no live switch)
        player.say(String.format("[ENGINE] undo-mode=%s spool-threshold=%dMB",
                ConfigProvider.engine().isColumnarUndo() ? "columnar" : "changeset",
                ConfigProvider.engine().getUndoSpoolThresholdMb()));

        player.say(MessageType.CMD_ENGINE_STATUS_COUNTERS.format(
                Integer.toString(JobBufferRegistry.getInstance().getBufferCount()),
                Integer.toString(SectionBudget.getShared().getUsed())));

        showMemorySnapshot(sender, player);
    }

    /**
     * Live memory + smoothness snapshot for the engine status command. Safe to
     * compute on demand (it is a manual status command, not a hot path): heap
     * used/max, the buffered section footprint (each pending section buffer
     * costs a fixed 32 KB), the classic queue footprint (~250 bytes per queued
     * block entry) and the current server TPS estimate.
     */
    private static void showMemorySnapshot(IAsyncWorldEditCore sender, IPlayerEntry player) {
        final Runtime rt = Runtime.getRuntime();
        final long usedBytes = rt.totalMemory() - rt.freeMemory();
        final long maxBytes = rt.maxMemory();
        final long usedMb = Math.round(usedBytes / (1024.0 * 1024.0));
        final long maxMb = Math.round(maxBytes / (1024.0 * 1024.0));
        final int pct = maxBytes > 0 ? (int) Math.round(usedBytes * 100.0 / maxBytes) : 0;

        final SectionBudget sb = SectionBudget.getShared();
        final int sectionsUsed = sb.getUsed();
        final int sectionsMax = sb.getMaxSections();
        //Each pending section buffer costs a fixed 32 KB (see SectionBudget)
        final String bufferedFootprint = EngineStats.formatMb(sectionsUsed * 32L * 1024L);

        final IBlockPlacer bp = sender.getBlockPlacer();
        final int classicQueue = bp.getGlobalQueueSize();
        //Classic queue entries cost ~250 bytes each (per block queue object)
        final String classicFootprint = EngineStats.formatMb(classicQueue * 250L);
        final double tps = bp.getTpsEstimate();

        player.say(String.format("[ENGINE] heap=%d/%dMB (%d%%) tps=%.1f",
                usedMb, maxMb, pct, tps));
        player.say(String.format(
                "[ENGINE] buffered-sections=%d/%d (~%s) classic-queue=%d (~%s @250B)",
                sectionsUsed, sectionsMax, bufferedFootprint, classicQueue, classicFootprint));
    }

    private static void switchToBuffered(IPlayerEntry player) {
        boolean active = ConfigProvider.setBufferedEngineActive(true);
        if (!active) {
            player.say(MessageType.CMD_ENGINE_REFUSED_ACCESS.format());
            return;
        }
        player.say(MessageType.CMD_ENGINE_SWITCHED.format(modeName(true)));
    }

    private static void switchToClassic(IAsyncWorldEditCore sender, final IPlayerEntry player) {
        ITaskDispatcher dispatcher = sender.getTaskDispatcher();

        // The force flush writes buffered blocks to the world, so it must run
        // on the main thread. Flushing before flipping the flag guarantees no
        // in-flight buffered blocks are stranded and none are double placed
        // (existing buffers are written exactly once, new jobs go classic).
        if (dispatcher.isMainTask()) {
            JobBufferRegistry.getInstance().forceFlush();
            ConfigProvider.setBufferedEngineActive(false);
            player.say(MessageType.CMD_ENGINE_SWITCHED.format(modeName(false)));
        } else {
            dispatcher.queueFastOperation(new IAction() {
                @Override
                public void execute() {
                    JobBufferRegistry.getInstance().forceFlush();
                    ConfigProvider.setBufferedEngineActive(false);
                    player.say(MessageType.CMD_ENGINE_SWITCHED.format(modeName(false)));
                }
            });
        }
    }

    private static void toggleDebug(IPlayerEntry player, String[] args) {
        if (args.length != 3) {
            Help.ShowHelp(player, Commands.COMMAND_ENGINE);
            return;
        }

        String arg = args[2];
        boolean enabled;
        if (arg.equalsIgnoreCase("on")) {
            enabled = true;
        } else if (arg.equalsIgnoreCase("off")) {
            enabled = false;
        } else {
            Help.ShowHelp(player, Commands.COMMAND_ENGINE);
            return;
        }

        EngineDebug.setEnabled(enabled);
        player.say(MessageType.CMD_ENGINE_DEBUG.format(enabled
                ? MessageType.GLOBAL_ON.format()
                : MessageType.GLOBAL_OFF.format()));
    }

    private static String modeName(boolean buffered) {
        return buffered
                ? MessageType.CMD_ENGINE_MODE_BUFFERED.format()
                : MessageType.CMD_ENGINE_MODE_CLASSIC.format();
    }
}
