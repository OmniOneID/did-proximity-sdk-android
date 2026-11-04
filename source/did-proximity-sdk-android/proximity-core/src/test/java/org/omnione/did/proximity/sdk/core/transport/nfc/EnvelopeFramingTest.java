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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.omnione.did.proximity.sdk.core.ProximityException;
import org.omnione.did.proximity.sdk.core.nfc.Apdu;
import org.omnione.did.proximity.sdk.core.session.IsoAnnexD;

import java.util.Arrays;

public class EnvelopeFramingTest {

    private static byte[] bytes(int length) {
        byte[] out = new byte[length];
        for (int i = 0; i < length; i++) {
            out[i] = (byte) (i * 7);
        }
        return out;
    }

    @Test
    public void do53UsesEveryBerLengthForm() {
        for (int length : new int[]{0, 1, 0x7F, 0x80, 0xFF, 0x100, 0xFFFF, 0x10000}) {
            byte[] data = bytes(length);
            byte[] encoded = EnvelopeFraming.encodeDo53(data);
            assertEquals(0x53, encoded[0] & 0xFF);
            int headerLength = length < 0x80 ? 2 : length < 0x100 ? 3 : length < 0x10000 ? 4 : 5;
            assertEquals(headerLength + length, encoded.length);
            assertArrayEquals(data, EnvelopeFraming.decodeDo53(encoded));
        }
    }

    @Test
    public void do53LengthFormsMatchMultipaz() {
        assertArrayEquals(IsoAnnexD.hex("530100"), EnvelopeFraming.encodeDo53(new byte[]{0}));
        assertEquals(0x81, EnvelopeFraming.encodeDo53(bytes(0x80))[1] & 0xFF);
        assertEquals(0x80, EnvelopeFraming.encodeDo53(bytes(0x80))[2] & 0xFF);
        byte[] extended = EnvelopeFraming.encodeDo53(bytes(0x1234));
        assertArrayEquals(IsoAnnexD.hex("53821234"), Arrays.copyOf(extended, 4));
        byte[] huge = EnvelopeFraming.encodeDo53(bytes(0x10000));
        assertArrayEquals(IsoAnnexD.hex("5383010000"), Arrays.copyOf(huge, 5));
    }

    @Test
    public void decodeRejectsIndefiniteLengthAndMismatchedLength() {
        assertThrows(ProximityException.class,
                () -> EnvelopeFraming.decodeDo53(IsoAnnexD.hex("538000")));
        assertThrows(ProximityException.class,
                () -> EnvelopeFraming.decodeDo53(IsoAnnexD.hex("530201")));
        assertThrows(ProximityException.class,
                () -> EnvelopeFraming.decodeDo53(IsoAnnexD.hex("53010102")));
        assertThrows(ProximityException.class,
                () -> EnvelopeFraming.decodeDo53(IsoAnnexD.hex("540100")));
        assertThrows(ProximityException.class,
                () -> EnvelopeFraming.decodeDo53(IsoAnnexD.hex("53")));
    }

    @Test
    public void decodeOfAnEmptyResponseMeansNoData() {
        assertNull(EnvelopeFraming.decodeDo53(new byte[0]));
    }

    @Test
    public void selectUsesTheDisParametersWithoutLe() {
        assertArrayEquals(IsoAnnexD.hex("00A4040C07A0000002480400"),
                EnvelopeFraming.selectMdocApplication());
        Apdu.Command command = Apdu.parseCommand(EnvelopeFraming.selectMdocApplication());
        assertEquals(0x0C, command.p2);
        assertEquals(0, command.le);
        assertArrayEquals(NfcDataTransfer.AID_MDOC_DATA, command.data);
    }

    @Test
    public void shortEnvelopeCarriesLeOnlyOnTheLastChunk() {
        byte[] chunk = IsoAnnexD.hex("530101");
        assertArrayEquals(IsoAnnexD.hex("10C3000003530101"),
                EnvelopeFraming.envelope(false, chunk, 256, false));
        assertArrayEquals(IsoAnnexD.hex("00C300000353010100"),
                EnvelopeFraming.envelope(true, chunk, 256, false));
        assertArrayEquals(IsoAnnexD.hex("00C3000003530101FF"),
                EnvelopeFraming.envelope(true, chunk, 255, false));
        Apdu.Command last = Apdu.parseCommand(EnvelopeFraming.envelope(true, chunk, 256, false));
        assertEquals(256, last.le);
        assertEquals(0, Apdu.parseCommand(EnvelopeFraming.envelope(false, chunk, 256, false)).le);
    }

    @Test
    public void extendedEnvelopeUsesThreeByteLcAndTwoByteLe() {
        byte[] chunk = IsoAnnexD.hex("530101");
        assertArrayEquals(IsoAnnexD.hex("10C30000000003530101"),
                EnvelopeFraming.envelope(false, chunk, 65536, true));
        assertArrayEquals(IsoAnnexD.hex("00C300000000035301010000"),
                EnvelopeFraming.envelope(true, chunk, 65536, true));
        assertArrayEquals(IsoAnnexD.hex("00C30000000003530101FFFF"),
                EnvelopeFraming.envelope(true, chunk, 65535, true));
        Apdu.Command last = Apdu.parseCommand(EnvelopeFraming.envelope(true, chunk, 65536, true));
        assertEquals(65536, last.le);
        assertArrayEquals(chunk, last.data);
        byte[] big = bytes(300);
        Apdu.Command chained = Apdu.parseCommand(EnvelopeFraming.envelope(false, big, 1, true));
        assertEquals(0x10, chained.cla);
        assertArrayEquals(big, chained.data);
        assertEquals(0, chained.le);
    }

    @Test
    public void envelopeRejectsChunksBeyondTheForm() {
        assertThrows(ProximityException.class,
                () -> EnvelopeFraming.envelope(true, bytes(256), 256, false));
        assertThrows(ProximityException.class,
                () -> EnvelopeFraming.envelope(true, bytes(65536), 256, true));
        assertThrows(ProximityException.class,
                () -> EnvelopeFraming.envelope(true, bytes(1), 257, false));
        assertThrows(ProximityException.class,
                () -> EnvelopeFraming.envelope(true, new byte[0], 256, false));
    }

    @Test
    public void getResponseShortAndExtended() {
        assertArrayEquals(IsoAnnexD.hex("00C0000000"), EnvelopeFraming.getResponse(256, false));
        assertArrayEquals(IsoAnnexD.hex("00C00000FF"), EnvelopeFraming.getResponse(255, false));
        assertArrayEquals(IsoAnnexD.hex("00C00000000000"), EnvelopeFraming.getResponse(65536, true));
        assertArrayEquals(IsoAnnexD.hex("00C000000001F4"), EnvelopeFraming.getResponse(500, true));
        assertEquals(256, Apdu.parseCommand(EnvelopeFraming.getResponse(256, false)).le);
        assertEquals(65536, Apdu.parseCommand(EnvelopeFraming.getResponse(65536, true)).le);
    }

    @Test
    public void moreDataMatchesThe61Family() {
        assertTrue(EnvelopeFraming.isMoreData(0x6100));
        assertTrue(EnvelopeFraming.isMoreData(0x61FF));
        assertFalse(EnvelopeFraming.isMoreData(0x9000));
        assertFalse(EnvelopeFraming.isMoreData(0x6A82));
    }
}
