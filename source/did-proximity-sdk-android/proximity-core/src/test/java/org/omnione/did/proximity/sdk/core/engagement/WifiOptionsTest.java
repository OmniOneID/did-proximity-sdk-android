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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

import com.upokecenter.cbor.CBORObject;

import org.junit.Test;
import org.omnione.did.proximity.sdk.core.ProximityException;

public class WifiOptionsTest {

    @Test
    public void everyKeyRoundTripsThroughCbor() {
        WifiOptions options = WifiOptions.of(new byte[]{0x14})
                .withPassphrase("secret")
                .withChannel(81, 6);

        WifiOptions parsed = WifiOptions.parse(options.toCbor());

        assertEquals(options, parsed);
        assertEquals("secret", parsed.passphrase());
        assertEquals(Integer.valueOf(81), parsed.operatingClass());
        assertEquals(Integer.valueOf(6), parsed.channelNumber());
        assertArrayEquals(new byte[]{0x14}, parsed.supportedBands());
    }

    @Test
    public void bandsOnlyEmitsExactlyKeyThree() {
        CBORObject cbor = WifiOptions.of(new byte[]{0x04}).toCbor();

        assertEquals(1, cbor.size());
        assertArrayEquals(new byte[]{0x04}, cbor.get(3).GetByteString());
    }

    @Test
    public void aMissingBandsKeyIsToleratedAsEmptyBands() {
        CBORObject cbor = CBORObject.NewMap();
        cbor.Add(0, "pp");

        WifiOptions parsed = WifiOptions.parse(cbor);

        assertArrayEquals(new byte[0], parsed.supportedBands());
        assertEquals("pp", parsed.passphrase());
        assertNull(parsed.operatingClass());
        assertNull(parsed.channelNumber());
    }

    @Test
    public void anEmptyBandsByteStringIsRejected() {
        CBORObject cbor = CBORObject.NewMap();
        cbor.Add(3, new byte[0]);

        assertThrows(ProximityException.class, () -> WifiOptions.parse(cbor));
    }

    @Test
    public void wrongTypesAreRejected() {
        CBORObject text = CBORObject.NewMap();
        text.Add(3, "not bytes");
        assertThrows(ProximityException.class, () -> WifiOptions.parse(text));

        CBORObject passphrase = CBORObject.NewMap();
        passphrase.Add(0, 1);
        assertThrows(ProximityException.class, () -> WifiOptions.parse(passphrase));

        CBORObject negative = CBORObject.NewMap();
        negative.Add(1, -1);
        assertThrows(ProximityException.class, () -> WifiOptions.parse(negative));

        assertThrows(ProximityException.class, () -> WifiOptions.parse(CBORObject.NewArray()));
    }

    @Test
    public void unknownKeysAreIgnored() {
        CBORObject cbor = CBORObject.NewMap();
        cbor.Add(3, new byte[]{0x14});
        cbor.Add(99, "future");

        assertEquals(WifiOptions.of(new byte[]{0x14}), WifiOptions.parse(cbor));
    }

    @Test
    public void emptyBandsCannotBeEmitted() {
        assertThrows(ProximityException.class, () -> WifiOptions.of(new byte[0]));
    }

    @Test
    public void equalityCoversEveryField() {
        WifiOptions a = WifiOptions.of(new byte[]{0x14});
        assertNotEquals(a, a.withPassphrase("x"));
        assertNotEquals(a, WifiOptions.of(new byte[]{0x04}));
        assertEquals(a.hashCode(), WifiOptions.of(new byte[]{0x14}).hashCode());
    }
}
