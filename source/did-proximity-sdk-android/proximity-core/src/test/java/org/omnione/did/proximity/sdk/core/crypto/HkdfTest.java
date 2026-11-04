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

package org.omnione.did.proximity.sdk.core.crypto;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;
import org.omnione.did.proximity.sdk.core.ProximityException;

public class HkdfTest {

    private static final byte[] IKM = hex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b");

    @Test
    public void rfc5869TestCase1Extract() {
        assertArrayEquals(
                hex("077709362c2e32df0ddc3f0dc47bba6390b6c73bb50f9c3122ec844ad7c2b3e5"),
                Hkdf.extract(hex("000102030405060708090a0b0c"), IKM));
    }

    @Test
    public void rfc5869TestCase1Expand() {
        assertArrayEquals(
                hex("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf"
                        + "34007208d5b887185865"),
                Hkdf.derive(hex("000102030405060708090a0b0c"), IKM,
                        hex("f0f1f2f3f4f5f6f7f8f9"), 42));
    }

    @Test
    public void rfc5869TestCase3HasNoSaltAndNoInfo() {
        assertArrayEquals(
                hex("19ef24a32c717b167f33a91d6f648bdf96596776afdb6377ac434c1c293ccb04"),
                Hkdf.extract(new byte[0], IKM));
        assertArrayEquals(
                hex("8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d"
                        + "9d201395faa4b61a96c8"),
                Hkdf.derive(new byte[0], IKM, new byte[0], 42));
    }

    @Test
    public void expandSpansMoreThanOneHashBlock() {
        byte[] okm = Hkdf.derive(new byte[0], IKM, new byte[0], 42);
        assertArrayEquals(
                hex("8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d"),
                java.util.Arrays.copyOf(okm, 32));
    }

    @Test
    public void sixteenOctetOutputIsATruncatedFirstBlock() {
        byte[] full = Hkdf.derive(new byte[0], IKM, new byte[0], 42);
        assertArrayEquals(java.util.Arrays.copyOf(full, 16),
                Hkdf.derive(new byte[0], IKM, new byte[0], 16));
    }

    @Test
    public void rejectsAnOutOfRangeLength() {
        assertThrows(ProximityException.class,
                () -> Hkdf.derive(new byte[0], IKM, new byte[0], 0));
        assertThrows(ProximityException.class,
                () -> Hkdf.derive(new byte[0], IKM, new byte[0], 255 * 32 + 1));
    }

    private static byte[] hex(String value) {
        byte[] out = new byte[value.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }
}
