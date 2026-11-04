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

package org.omnione.did.proximity.sdk.core.nfc;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.omnione.did.proximity.sdk.core.ProximityException;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public class NdefMessageTest {

    private static final int FLAG_MB = 0x80;
    private static final int FLAG_ME = 0x40;
    private static final int FLAG_CF = 0x20;
    private static final int FLAG_SR = 0x10;
    private static final int FLAG_IL = 0x08;

    private static NdefRecord wellKnown(String type, byte[] payload) {
        return NdefRecord.of(NdefRecord.TNF_WELL_KNOWN, type, null, payload);
    }

    @Test
    public void singleRecordSetsBothMbAndMe() {
        byte[] encoded = NdefMessage.of(wellKnown("Hs", new byte[]{0x15})).encode();

        int header = encoded[0] & 0xFF;
        assertTrue("MB", (header & FLAG_MB) != 0);
        assertTrue("ME", (header & FLAG_ME) != 0);
        assertTrue("SR", (header & FLAG_SR) != 0);
        assertEquals("IL must be clear when there is no id", 0, header & FLAG_IL);
        assertEquals(NdefRecord.TNF_WELL_KNOWN, header & 0x07);
    }

    @Test
    public void threeRecordsCarryMbOnFirstAndMeOnLastOnly() {
        NdefMessage message = NdefMessage.of(
                wellKnown("Hs", new byte[]{0x15}),
                NdefRecord.of(NdefRecord.TNF_MIME_MEDIA, "application/vnd.bluetooth.le.oob", "0",
                        new byte[]{0x02, 0x1C, 0x01}),
                NdefRecord.of(NdefRecord.TNF_EXTERNAL_TYPE, "iso.org:18013:deviceengagement", "mdoc",
                        new byte[]{(byte) 0xA2}));

        NdefMessage reparsed = NdefMessage.parse(message.encode());

        assertEquals(3, reparsed.records().size());
        assertArrayEquals(message.encode(), reparsed.encode());
        assertTrue(reparsed.records().get(0).is(NdefRecord.TNF_WELL_KNOWN, "Hs"));
        assertTrue(reparsed.records().get(1).hasId("0"));
        assertTrue(reparsed.records().get(2).hasId("mdoc"));
    }

    @Test
    public void idFieldSurvivesRoundTripAndIsAbsentWhenNull() {
        NdefRecord withId = NdefRecord.of(NdefRecord.TNF_EXTERNAL_TYPE, "iso.org:18013:nfc", "nfc",
                new byte[]{0x01});
        NdefRecord withoutId = wellKnown("ac", new byte[]{0x01, 0x01, 0x30, 0x00});

        NdefMessage reparsed = NdefMessage.parse(NdefMessage.of(withId, withoutId).encode());

        assertArrayEquals("nfc".getBytes(StandardCharsets.UTF_8), reparsed.records().get(0).id());
        assertNull(reparsed.records().get(1).id());
        assertNotNull(reparsed.findById(NdefRecord.TNF_EXTERNAL_TYPE, "iso.org:18013:nfc", "nfc"));
        assertNull(reparsed.findById(NdefRecord.TNF_EXTERNAL_TYPE, "iso.org:18013:nfc", "mdoc"));
    }

    @Test
    public void payloadOf255UsesShortRecordAnd256UsesNormalForm() {
        byte[] encoded255 = NdefMessage.of(wellKnown("T", new byte[255])).encode();
        byte[] encoded256 = NdefMessage.of(wellKnown("T", new byte[256])).encode();

        assertTrue("255 bytes must stay a short record", (encoded255[0] & FLAG_SR) != 0);
        assertEquals(255, encoded255[2] & 0xFF);

        assertEquals("256 bytes must drop SR", 0, encoded256[0] & FLAG_SR);
        assertEquals(0, encoded256[2] & 0xFF);
        assertEquals(0, encoded256[3] & 0xFF);
        assertEquals(1, encoded256[4] & 0xFF);
        assertEquals(0, encoded256[5] & 0xFF);
    }

    @Test
    public void bothLengthFormsReparseToTheSamePayload() {
        byte[] short255 = new byte[255];
        byte[] long256 = new byte[256];
        Arrays.fill(short255, (byte) 0x7E);
        Arrays.fill(long256, (byte) 0x7F);

        assertArrayEquals(short255,
                NdefMessage.parse(NdefMessage.of(wellKnown("T", short255)).encode())
                        .records().get(0).payload());
        assertArrayEquals(long256,
                NdefMessage.parse(NdefMessage.of(wellKnown("T", long256)).encode())
                        .records().get(0).payload());
    }

    @Test
    public void chunkedRecordsAreRejected() {
        byte[] chunked = {
                (byte) (FLAG_MB | FLAG_CF | FLAG_SR | 0x01), 0x01, 0x01, 'T', 0x00
        };

        ProximityException error =
                assertThrows(ProximityException.class, () -> NdefMessage.parse(chunked));
        assertTrue(error.getMessage(), error.getMessage().contains("chunked"));
    }

    @Test
    public void truncatedMessageIsRejected() {
        byte[] full = NdefMessage.of(wellKnown("Hs", new byte[]{0x15, 0x00, 0x00})).encode();

        for (int cut = 1; cut < full.length; cut++) {
            byte[] truncated = Arrays.copyOf(full, cut);
            assertThrows("cut at " + cut, ProximityException.class,
                    () -> NdefMessage.parse(truncated));
        }
    }

    @Test
    public void trailingBytesAfterMeAreRejected() {
        byte[] full = NdefMessage.of(wellKnown("Hs", new byte[]{0x15})).encode();
        byte[] withTrailer = Arrays.copyOf(full, full.length + 1);

        ProximityException error =
                assertThrows(ProximityException.class, () -> NdefMessage.parse(withTrailer));
        assertTrue(error.getMessage(), error.getMessage().contains("trailing"));
    }

    @Test
    public void emptyMessageIsRejected() {
        assertThrows(ProximityException.class, () -> NdefMessage.parse(new byte[0]));
        assertThrows(ProximityException.class, () -> NdefMessage.of());
    }

    @Test
    public void firstRecordWithoutMbIsRejected() {
        byte[] noMb = {(byte) (FLAG_ME | FLAG_SR | 0x01), 0x01, 0x00, 'T'};

        ProximityException error =
                assertThrows(ProximityException.class, () -> NdefMessage.parse(noMb));
        assertTrue(error.getMessage(), error.getMessage().contains("MB"));
    }

    @Test
    public void messageEndingWithoutMeIsRejected() {
        byte[] noMe = {(byte) (FLAG_MB | FLAG_SR | 0x01), 0x01, 0x00, 'T'};

        ProximityException error =
                assertThrows(ProximityException.class, () -> NdefMessage.parse(noMe));
        assertTrue(error.getMessage(), error.getMessage().contains("ME"));
    }

    @Test
    public void accessorsReturnDefensiveCopies() {
        byte[] payload = {0x01, 0x02};
        NdefRecord record = NdefRecord.of(NdefRecord.TNF_MIME_MEDIA, "x/y", "0", payload);

        payload[0] = 0x7F;
        record.payload()[1] = 0x7F;

        assertArrayEquals(new byte[]{0x01, 0x02}, record.payload());
    }
}
