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

import org.junit.Test;
import org.omnione.did.proximity.sdk.core.ProximityException;
import org.omnione.did.proximity.sdk.core.engagement.NfcOptions;
import org.omnione.did.proximity.sdk.core.session.IsoAnnexD;

public class NfcCarrierTest {

    @Test
    public void encodesLengthTypeValueWithMinimalBigEndianValues() {
        assertArrayEquals(IsoAnnexD.hex("010301FFFF0402010000"),
                NfcCarrier.readerCarrier(NfcOptions.of(65535, 65536)).encode());
        assertArrayEquals(IsoAnnexD.hex("010201FF03020100"),
                NfcCarrier.of(NfcOptions.of(255, 256)).encode());
    }

    @Test
    public void roundTripsThroughParse() {
        NfcOptions options = NfcOptions.of(4096, 8192);
        NfcCarrier parsed = NfcCarrier.parse(NfcCarrier.of(options).encode());
        assertEquals(options, parsed.options());
    }

    @Test
    public void theAnnexD32ReaderRecordCarriesOnlyTheVersion() {
        NfcCarrier parsed = NfcCarrier.parse(new byte[]{0x01});
        assertNull(parsed.options());
        assertArrayEquals(new byte[]{0x01}, parsed.encode());
    }

    @Test
    public void unknownFieldTypesAreSkipped() {
        NfcCarrier parsed = NfcCarrier.parse(IsoAnnexD.hex("01027FAA0301FFFF0402010000"));
        assertEquals(NfcOptions.of(65535, 65536), parsed.options());
    }

    @Test
    public void anotherVersionIsRejected() {
        assertThrows(ProximityException.class, () -> NfcCarrier.parse(new byte[]{0x02}));
        assertThrows(ProximityException.class, () -> NfcCarrier.parse(new byte[0]));
        assertThrows(ProximityException.class, () -> NfcCarrier.parse(IsoAnnexD.hex("010501")));
    }

    @Test
    public void limitsOutOfRangeLeaveTheOptionsUnset() {
        assertNull(NfcCarrier.parse(IsoAnnexD.hex("0102010003020100")).options());
        assertNull(NfcCarrier.parse(IsoAnnexD.hex("010201FF0202FF")).options());
        assertNull(NfcCarrier.parse(IsoAnnexD.hex("010201FF")).options());
    }

    @Test
    public void ofNullOptionsIsNoCarrier() {
        assertNull(NfcCarrier.of(null));
        assertNotNull(NfcCarrier.of(NfcOptions.of(255, 256)));
    }
}
