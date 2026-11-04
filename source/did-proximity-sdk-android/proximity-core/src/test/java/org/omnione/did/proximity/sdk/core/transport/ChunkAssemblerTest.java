/*
 * Copyright 2026 OmniOne.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.omnione.did.proximity.sdk.core.transport;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

import org.junit.Test;
import org.omnione.did.proximity.sdk.core.ProximityException;

import java.util.List;
import java.util.Random;

public class ChunkAssemblerTest {

    @Test
    public void theChunkSizeFollowsTheMtuBelowTheAttributeLimit() {
        assertEquals(20, ChunkAssembler.maxChunkSize(23));
        assertEquals(512, ChunkAssembler.maxChunkSize(515));
    }

    @Test
    public void theChunkSizeIsCappedAtTheAttributeValueLimit() {
        assertEquals(512, ChunkAssembler.maxChunkSize(517));
        assertEquals(512, ChunkAssembler.maxChunkSize(1000));
    }

    @Test
    public void aRealisticResponseNeverExceedsTheAttributeValueLimitAtMtu517() {
        byte[] response = new byte[1138];

        for (byte[] chunk : ChunkAssembler.split(response, ChunkAssembler.maxChunkSize(517))) {
            assertEquals(true, chunk.length <= 512);
        }
    }

    @Test
    public void shortMessageBecomesSingleFinalChunk() {
        List<byte[]> chunks = ChunkAssembler.split(new byte[]{1, 2, 3}, 10);

        assertEquals(1, chunks.size());
        assertEquals(0x00, chunks.get(0)[0]);
        assertArrayEquals(new byte[]{0x00, 1, 2, 3}, chunks.get(0));
    }

    @Test
    public void longMessageIsSplitWithContinuationFlags() {
        byte[] message = new byte[10];
        for (int i = 0; i < message.length; i++) {
            message[i] = (byte) i;
        }

        List<byte[]> chunks = ChunkAssembler.split(message, 5);

        assertEquals(3, chunks.size());
        assertEquals(0x01, chunks.get(0)[0]);
        assertEquals(0x01, chunks.get(1)[0]);
        assertEquals(0x00, chunks.get(2)[0]);
        for (byte[] chunk : chunks) {
            assertEquals(true, chunk.length <= 5);
        }
    }

    @Test
    public void splitAndAppendRoundTripsLargeMessage() {
        byte[] message = new byte[4096];
        new Random(42).nextBytes(message);
        ChunkAssembler assembler = new ChunkAssembler();

        byte[] assembled = null;
        for (byte[] chunk : ChunkAssembler.split(message, 512)) {
            assembled = assembler.append(chunk);
        }

        assertArrayEquals(message, assembled);
    }

    @Test
    public void appendReturnsNullUntilFinalChunk() {
        ChunkAssembler assembler = new ChunkAssembler();
        List<byte[]> chunks = ChunkAssembler.split(new byte[20], 5);

        assertNull(assembler.append(chunks.get(0)));
    }

    @Test
    public void rejectsChunkWithUnknownFlag() {
        ChunkAssembler assembler = new ChunkAssembler();

        assertThrows(ProximityException.class,
                () -> assembler.append(new byte[]{0x07, 1, 2}));
    }

    @Test
    public void rejectedChunkDoesNotCorruptTheNextMessage() {
        ChunkAssembler assembler = new ChunkAssembler();
        assembler.append(new byte[]{ChunkAssembler.MORE_COMING, 9, 9, 9});

        assertThrows(ProximityException.class,
                () -> assembler.append(new byte[]{0x07, 1, 2}));

        byte[] assembled = assembler.append(new byte[]{ChunkAssembler.LAST, 1, 2, 3});
        assertArrayEquals(new byte[]{1, 2, 3}, assembled);
    }

    @Test
    public void rejectsMaxChunkSizeBelowTwo() {
        assertThrows(ProximityException.class,
                () -> ChunkAssembler.split(new byte[]{1}, 1));
    }

    @Test
    public void appendStopsAtTheMessageSizeCap() {
        ChunkAssembler assembler = new ChunkAssembler();
        byte[] chunk = new byte[8193];
        chunk[0] = ChunkAssembler.MORE_COMING;
        int payload = chunk.length - 1;
        int accepted = ChunkAssembler.MAX_MESSAGE_BYTES / payload;

        for (int i = 0; i < accepted; i++) {
            assertNull(assembler.append(chunk));
        }

        assertThrows(ProximityException.class, () -> assembler.append(chunk));
    }

    @Test
    public void exceedingTheCapLeavesTheAssemblerReusable() {
        ChunkAssembler assembler = new ChunkAssembler();
        byte[] chunk = new byte[8193];
        chunk[0] = ChunkAssembler.MORE_COMING;
        int accepted = ChunkAssembler.MAX_MESSAGE_BYTES / (chunk.length - 1);
        for (int i = 0; i < accepted; i++) {
            assembler.append(chunk);
        }
        assertThrows(ProximityException.class, () -> assembler.append(chunk));

        byte[] assembled = assembler.append(new byte[]{ChunkAssembler.LAST, 4, 5, 6});

        assertArrayEquals(new byte[]{4, 5, 6}, assembled);
    }

    @Test
    public void splitAcceptsExactlyTheCapAndRejectsOneByteMore() {
        byte[] atCap = new byte[ChunkAssembler.MAX_MESSAGE_BYTES];
        byte[] oversized = new byte[ChunkAssembler.MAX_MESSAGE_BYTES + 1];

        List<byte[]> chunks = ChunkAssembler.split(atCap, 512);
        assertEquals((ChunkAssembler.MAX_MESSAGE_BYTES + 510) / 511, chunks.size());
        assertEquals(ChunkAssembler.LAST, chunks.get(chunks.size() - 1)[0]);
        assertThrows(ProximityException.class, () -> ChunkAssembler.split(oversized, 512));
    }
}
