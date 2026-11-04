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

package org.omnione.did.proximity.sdk.core.transport.ble;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import org.junit.Test;

public class BleIdentTest {

    private static final String RUN14_E_DEVICE_KEY_BYTES =
            "d818584ba4010220012158200a6ab654be85f59456608c903bdf34e7c64d8fd9fbc894"
                    + "8f2faabecabd767c82225820b0fc54ee092a66cf8d63f39c37c3108c5f57fd7af8"
                    + "f110a9327415e013bc39ba";

    @Test
    public void identIsSixteenOctets() {
        assertEquals(16, BleIdent.derive(hex(RUN14_E_DEVICE_KEY_BYTES)).length);
    }

    @Test
    public void identMatchesTheMultipazRun14Engagement() {
        assertArrayEquals(hex("e0eb935487f501e9bcc7d1142e4d5def"),
                BleIdent.derive(hex(RUN14_E_DEVICE_KEY_BYTES)));
    }

    @Test
    public void aDifferentDeviceKeyProducesADifferentIdent() {
        byte[] altered = hex(RUN14_E_DEVICE_KEY_BYTES);
        altered[altered.length - 1] ^= 0x01;

        assertNotEquals(
                java.util.Arrays.toString(BleIdent.derive(hex(RUN14_E_DEVICE_KEY_BYTES))),
                java.util.Arrays.toString(BleIdent.derive(altered)));
    }

    private static byte[] hex(String value) {
        byte[] out = new byte[value.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }
}
