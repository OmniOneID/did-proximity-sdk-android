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

package org.omnione.did.proximity.sdk.core.engagement;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;

import com.upokecenter.cbor.CBORObject;

import org.junit.Test;
import org.omnione.did.proximity.sdk.core.ProximityException;

public class NfcOptionsTest {

    @Test
    public void roundTripsThroughCbor() {
        NfcOptions options = NfcOptions.of(65535, 65536);
        CBORObject cbor = options.toCbor();
        assertEquals(65535, cbor.get(0).AsInt32Value());
        assertEquals(65536, cbor.get(1).AsInt32Value());
        assertEquals(options, NfcOptions.parse(CBORObject.DecodeFromBytes(cbor.EncodeToBytes())));
        assertEquals(options.hashCode(), NfcOptions.of(65535, 65536).hashCode());
        assertNotEquals(options, NfcOptions.of(255, 256));
    }

    @Test
    public void encodesTheMinimumLimitsAsExpected() {
        assertArrayEquals(new byte[]{(byte) 0xA2, 0x00, 0x18, (byte) 0xFF, 0x01, 0x19, 0x01, 0x00},
                NfcOptions.of(255, 256).toCbor().EncodeToBytes());
    }

    @Test
    public void rejectsLimitsOutsideTheSpecRange() {
        assertThrows(ProximityException.class, () -> NfcOptions.of(254, 256));
        assertThrows(ProximityException.class, () -> NfcOptions.of(65536, 256));
        assertThrows(ProximityException.class, () -> NfcOptions.of(255, 255));
        assertThrows(ProximityException.class, () -> NfcOptions.of(255, 65537));
    }

    @Test
    public void parseRequiresBothKeysAsUnsignedIntegers() {
        CBORObject missing = CBORObject.NewMap();
        missing.Add(0, 255);
        assertThrows(ProximityException.class, () -> NfcOptions.parse(missing));

        CBORObject negative = CBORObject.NewMap();
        negative.Add(0, -1);
        negative.Add(1, 256);
        assertThrows(ProximityException.class, () -> NfcOptions.parse(negative));

        CBORObject text = CBORObject.NewMap();
        text.Add(0, "255");
        text.Add(1, 256);
        assertThrows(ProximityException.class, () -> NfcOptions.parse(text));

        assertThrows(ProximityException.class,
                () -> NfcOptions.parse(CBORObject.NewArray()));
    }
}
