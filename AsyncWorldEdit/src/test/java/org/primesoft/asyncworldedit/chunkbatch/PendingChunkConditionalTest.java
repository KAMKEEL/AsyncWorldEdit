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

import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;
import org.junit.Test;

/**
 * Exact-value tests for conditional (//replace fast lane) pending slots:
 * produce-time semantics against earlier writes, overlay visibility,
 * condition conflicts and the flush-time resolution against the pre-write
 * world.
 *
 * @author KAMKEEL
 */
public class PendingChunkConditionalTest {

    /**
     * A reader over a flat fake world: worldId/worldData everywhere except
     * the positions listed in other (which read otherId/otherData) and the
     * positions in unreadable (which miss)
     */
    private static PendingChunk.IOldValueReader reader(final int worldId,
            final int worldData, final String[] other, final int otherId,
            final int otherData, final String... unreadable) {
        return new PendingChunk.IOldValueReader() {
            @Override
            public int read(int x, int y, int z) {
                String key = x + "," + y + "," + z;
                for (String u : unreadable) {
                    if (u.equals(key)) {
                        return SectionMath.EMPTY_SLOT;
                    }
                }
                if (other != null) {
                    for (String o : other) {
                        if (o.equals(key)) {
                            return SectionMath.encodeSlot(otherId, otherData, false);
                        }
                    }
                }
                return SectionMath.encodeSlot(worldId, worldData, false);
            }
        };
    }

    @Test
    public void conditionalFillStoresCountsAndHidesFromTheOverlay() {
        PendingChunk chunk = new PendingChunk(0, 0);
        int added = chunk.fillBoxConditional(0, 0, 0, 3, 3, 3, 9, 1, false, 5, 1, 0);

        assertEquals(64, added);
        assertEquals(64, chunk.getCount());
        assertTrue(chunk.hasConditional());
        assertEquals(64, chunk.getSection(0).getConditionalCount());
        assertEquals(1, chunk.getSection(0).getMatchId());
        assertEquals(0, chunk.getSection(0).getMatchData());

        //Overlay semantics: a conditional slot is unknowable until flush,
        //read-your-writes must fall through to the world
        assertEquals(SectionMath.EMPTY_SLOT, chunk.getPendingSlot(1, 1, 1));
        //The raw slot still holds the replacement for the flush machinery
        int index = SectionMath.sectionIndex(1, 1, 1);
        assertTrue(chunk.getSection(0).isConditional(index));
        assertEquals(9, SectionMath.slotId(chunk.getSection(0).getSlot(index)));
    }

    @Test
    public void conditionalOverEarlierPendingValueResolvesAtProduceTime() {
        PendingChunk chunk = new PendingChunk(0, 0);
        //Two earlier unconditional writes: one matching the condition,
        //one not
        assertTrue(chunk.setBlock(1, 1, 1, 5, 2, false, 1));
        assertTrue(chunk.setBlock(2, 1, 1, 7, 0, false, 2));

        chunk.fillBoxConditional(0, 1, 0, 3, 1, 3, 9, 0, false, 3, 5, 2);

        //Matching pending value: replaced, unconditional
        int hit = chunk.getPendingSlot(1, 1, 1);
        assertEquals(9, SectionMath.slotId(hit));
        assertFalse(chunk.getSection(0).isConditional(SectionMath.sectionIndex(1, 1, 1)));
        //Non-matching pending value: untouched
        int miss = chunk.getPendingSlot(2, 1, 1);
        assertEquals(7, SectionMath.slotId(miss));
        //Everything else in the 4x1x4 box is conditional (16 - 2 slots)
        assertEquals(14, chunk.getSection(0).getConditionalCount());
    }

    @Test
    public void unconditionalOverwriteAndClearDropTheCondition() {
        PendingChunk chunk = new PendingChunk(0, 0);
        chunk.fillBoxConditional(0, 0, 0, 1, 0, 0, 9, 0, false, 1, 5, -1);
        assertTrue(chunk.hasConditional());

        //Later unconditional write wins outright
        assertTrue(chunk.setBlock(0, 0, 0, 3, 3, false, 2));
        assertFalse(chunk.getSection(0).isConditional(SectionMath.sectionIndex(0, 0, 0)));
        assertEquals(3, SectionMath.slotId(chunk.getPendingSlot(0, 0, 0)));

        //Clear drops the slot and its condition
        assertTrue(chunk.clear(1, 0, 0));
        assertFalse(chunk.hasConditional());
        assertEquals(1, chunk.getCount());
    }

    @Test
    public void differentConditionIsRefusedWithoutPartialState() {
        PendingChunk chunk = new PendingChunk(0, 0);
        assertEquals(4, chunk.fillBoxConditional(0, 0, 0, 1, 0, 1, 9, 0, false, 1, 5, 0));
        int countBefore = chunk.getCount();

        //Same section, different condition: refused, nothing stored
        assertEquals(-1, chunk.fillBoxConditional(4, 0, 4, 5, 0, 5, 9, 0, false, 2, 6, 0));
        assertEquals(countBefore, chunk.getCount());
        assertEquals(4, chunk.getSection(0).getConditionalCount());

        //Same condition again: accepted
        assertEquals(4, chunk.fillBoxConditional(4, 0, 4, 5, 0, 5, 9, 0, false, 3, 5, 0));
    }

    @Test
    public void resolveKeepsMatchesDropsNonMatchesAndCountsMisses() {
        PendingChunk chunk = new PendingChunk(0, 0);
        //4 slots at y=0: the world holds 5:2 at (0,0,0) and (1,0,0),
        //7:1 at (0,0,1), unreadable at (1,0,1)
        chunk.fillBoxConditional(0, 0, 0, 1, 0, 1, 9, 3, false, 1, 5, 2);

        int misses = chunk.resolveConditionals(reader(5, 2,
                new String[]{"0,0,1"}, 7, 1, "1,0,1"));

        assertEquals(1, misses);
        assertFalse(chunk.hasConditional());
        //Matches became ordinary pending slots with the replacement
        int slot = chunk.getPendingSlot(0, 0, 0);
        assertEquals(9, SectionMath.slotId(slot));
        assertEquals(3, SectionMath.slotData(slot));
        assertEquals(9, SectionMath.slotId(chunk.getPendingSlot(1, 0, 0)));
        //Non-match and unreadable: cleared entirely
        assertEquals(SectionMath.EMPTY_SLOT, chunk.getPendingSlot(0, 0, 1));
        assertEquals(SectionMath.EMPTY_SLOT, chunk.getPendingSlot(1, 0, 1));
        assertEquals(2, chunk.getCount());
    }

    @Test
    public void wildcardDataMatchesAnyMetadata() {
        PendingChunk chunk = new PendingChunk(0, 0);
        chunk.fillBoxConditional(0, 0, 0, 1, 0, 0, 9, 0, false, 1, 5, -1);

        //The world holds the same id with different metadata per position
        int misses = chunk.resolveConditionals(reader(5, 7,
                new String[]{"1,0,0"}, 5, 11));

        assertEquals(0, misses);
        assertEquals(9, SectionMath.slotId(chunk.getPendingSlot(0, 0, 0)));
        assertEquals(9, SectionMath.slotId(chunk.getPendingSlot(1, 0, 0)));
        assertEquals(2, chunk.getCount());
    }

    @Test
    public void matchAirFillsOnlyTheAirPositions() {
        PendingChunk chunk = new PendingChunk(0, 0);
        ////replace 0 stone: match air, wildcard data
        chunk.fillBoxConditional(0, 16, 0, 3, 16, 3, 1, 0, false, 1, 0, -1);

        int misses = chunk.resolveConditionals(reader(0, 0,
                new String[]{"2,16,2"}, 3, 0));

        assertEquals(0, misses);
        assertEquals(1, SectionMath.slotId(chunk.getPendingSlot(0, 16, 0)));
        //The non-air position stays untouched
        assertEquals(SectionMath.EMPTY_SLOT, chunk.getPendingSlot(2, 16, 2));
        assertEquals(15, chunk.getCount());
    }

    @Test
    public void resolvedChunkReplaysOnlyTheMatches() {
        PendingChunk chunk = new PendingChunk(0, 0);
        chunk.fillBoxConditional(0, 0, 0, 1, 0, 1, 9, 0, false, 4, 5, 0);
        chunk.resolveConditionals(reader(5, 0, new String[]{"1,0,1"}, 2, 0));

        final List<String> visited = new ArrayList<String>();
        chunk.forEachLastWriteOrder(new PendingChunk.IPendingBlockVisitor() {
            @Override
            public void visit(int x, int y, int z, int id, int data,
                    boolean notify, int seq) {
                assertEquals(9, id);
                assertEquals(4, seq);
                visited.add(x + "," + y + "," + z);
            }
        });
        assertEquals(3, visited.size());
        assertFalse(visited.contains("1,0,1"));
    }
}
