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

package org.omnione.did.proximity.sdk.core.session;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;

import com.upokecenter.cbor.CBORObject;

import org.junit.Test;
import org.omnione.did.proximity.sdk.core.ProximityException;
import org.omnione.did.proximity.sdk.core.cbor.CoseKeys;
import org.omnione.did.proximity.sdk.core.engagement.SessionTranscripts;

import java.security.KeyPair;
import java.security.interfaces.ECPublicKey;
import java.util.Arrays;

public class SessionKeysTest {

    private static final byte[] TRANSCRIPT =
            SessionTranscripts.forQr(new byte[]{(byte) 0xA0}, new byte[]{(byte) 0xA1});

    @Test
    public void bothSidesDeriveIdenticalKeys() {
        KeyPair device = CoseKeys.generateEphemeral();
        KeyPair reader = CoseKeys.generateEphemeral();

        SessionKeys fromDevice = SessionKeys.derive(
                device.getPrivate(), (ECPublicKey) reader.getPublic(), TRANSCRIPT);
        SessionKeys fromReader = SessionKeys.derive(
                reader.getPrivate(), (ECPublicKey) device.getPublic(), TRANSCRIPT);

        assertArrayEquals(fromDevice.deviceKey(), fromReader.deviceKey());
        assertArrayEquals(fromDevice.readerKey(), fromReader.readerKey());
    }

    @Test
    public void deviceAndReaderKeysDiffer() {
        KeyPair device = CoseKeys.generateEphemeral();
        KeyPair reader = CoseKeys.generateEphemeral();

        SessionKeys keys = SessionKeys.derive(
                device.getPrivate(), (ECPublicKey) reader.getPublic(), TRANSCRIPT);

        assertEquals(32, keys.deviceKey().length);
        assertEquals(32, keys.readerKey().length);
        assertFalse(Arrays.equals(keys.deviceKey(), keys.readerKey()));
    }

    @Test
    public void differentTranscriptProducesDifferentKeys() {
        KeyPair device = CoseKeys.generateEphemeral();
        KeyPair reader = CoseKeys.generateEphemeral();
        byte[] other = SessionTranscripts.forQr(new byte[]{(byte) 0xA0}, new byte[]{(byte) 0xA2});

        SessionKeys first = SessionKeys.derive(
                device.getPrivate(), (ECPublicKey) reader.getPublic(), TRANSCRIPT);
        SessionKeys second = SessionKeys.derive(
                device.getPrivate(), (ECPublicKey) reader.getPublic(), other);

        assertFalse(Arrays.equals(first.deviceKey(), second.deviceKey()));
    }

    @Test
    public void offCurveRemoteKeyFailsAsProximityException() {
        KeyPair device = CoseKeys.generateEphemeral();
        for (byte[] coordinate : new byte[][]{{0x00}, {0x01}}) {
            CBORObject coseKey = CBORObject.NewMap();
            coseKey.Add(CoseKeys.LABEL_KTY, CoseKeys.KTY_EC2);
            coseKey.Add(CoseKeys.LABEL_CRV, CoseKeys.CRV_P256);
            coseKey.Add(CoseKeys.LABEL_X, coordinate);
            coseKey.Add(CoseKeys.LABEL_Y, coordinate);
            ECPublicKey offCurve = CoseKeys.toPublicKey(coseKey);

            assertThrows(ProximityException.class,
                    () -> SessionKeys.derive(device.getPrivate(), offCurve, TRANSCRIPT));
        }
    }
}
