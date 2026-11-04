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

package org.omnione.did.proximity.sdk.core.transport.nfc;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.omnione.did.proximity.sdk.core.nfc.Apdu;

import java.util.Arrays;

public class NfcResponseChunkerTest {

    private static byte[] bytes(int length) {
        byte[] out = new byte[length];
        for (int i = 0; i < length; i++) {
            out[i] = (byte) i;
        }
        return out;
    }

    @Test
    public void everythingFitsWhenLeCoversTheAvailableBytes() {
        byte[] payload = bytes(100);
        NfcResponseChunker chunker = new NfcResponseChunker(payload, 100);
        Apdu.Response response = chunker.next();
        assertArrayEquals(payload, response.data);
        assertEquals(0x9000, response.status);
        assertFalse(chunker.hasMore());
    }

    @Test
    public void remainingWithin255IsReportedInTheStatusWord() {
        byte[] payload = bytes(300);
        NfcResponseChunker chunker = new NfcResponseChunker(payload, 200);
        Apdu.Response first = chunker.next();
        assertEquals(200, first.data.length);
        assertEquals(0x6164, first.status);
        assertTrue(chunker.hasMore());
        Apdu.Response second = chunker.next();
        assertArrayEquals(Arrays.copyOfRange(payload, 200, 300), second.data);
        assertEquals(0x9000, second.status);
        assertFalse(chunker.hasMore());
    }

    @Test
    public void remainingBeyond255IsReportedAs6100() {
        byte[] payload = bytes(1000);
        NfcResponseChunker chunker = new NfcResponseChunker(payload, 256);
        Apdu.Response first = chunker.next();
        assertEquals(256, first.data.length);
        assertEquals(0x6100, first.status);
        Apdu.Response second = chunker.next();
        assertEquals(0x6100, second.status);
        Apdu.Response third = chunker.next();
        assertEquals(0x61E8, third.status);
        Apdu.Response fourth = chunker.next();
        assertEquals(232, fourth.data.length);
        assertEquals(0x9000, fourth.status);
        assertFalse(chunker.hasMore());
    }

    @Test
    public void exactMultipleEndsWith9000OnTheLastChunk() {
        NfcResponseChunker chunker = new NfcResponseChunker(bytes(512), 256);
        assertEquals(0x6100, chunker.next().status);
        assertEquals(0x9000, chunker.next().status);
    }

    @Test
    public void emptyPayloadAnswersWithABare9000() {
        NfcResponseChunker chunker = new NfcResponseChunker(new byte[0], 256);
        assertFalse(chunker.hasMore());
        Apdu.Response response = chunker.next();
        assertEquals(0, response.data.length);
        assertEquals(0x9000, response.status);
    }
}
