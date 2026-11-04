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

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.interfaces.ECPublicKey;
import java.util.Arrays;

public class SessionCryptoTest {

    private static final byte[] TRANSCRIPT =
            SessionTranscripts.forQr(new byte[]{(byte) 0xA0}, new byte[]{(byte) 0xA1});

    private static SessionKeys keys() {
        KeyPair device = CoseKeys.generateEphemeral();
        KeyPair reader = CoseKeys.generateEphemeral();
        return SessionKeys.derive(device.getPrivate(), (ECPublicKey) reader.getPublic(), TRANSCRIPT);
    }

    @Test
    public void readerDecryptsWhatDeviceEncrypted() {
        SessionKeys shared = keys();
        SessionCrypto device = SessionCrypto.forDevice(shared);
        SessionCrypto reader = SessionCrypto.forReader(shared);
        byte[] plaintext = "device response".getBytes(StandardCharsets.UTF_8);

        byte[] decrypted = reader.decrypt(device.encrypt(plaintext));

        assertArrayEquals(plaintext, decrypted);
    }

    @Test
    public void deviceDecryptsWhatReaderEncrypted() {
        SessionKeys shared = keys();
        SessionCrypto device = SessionCrypto.forDevice(shared);
        SessionCrypto reader = SessionCrypto.forReader(shared);
        byte[] plaintext = "device request".getBytes(StandardCharsets.UTF_8);

        byte[] decrypted = device.decrypt(reader.encrypt(plaintext));

        assertArrayEquals(plaintext, decrypted);
    }

    @Test
    public void counterAdvancesSoIdenticalPlaintextsDiffer() {
        SessionCrypto device = SessionCrypto.forDevice(keys());
        byte[] plaintext = "same".getBytes(StandardCharsets.UTF_8);

        byte[] first = device.encrypt(plaintext);
        byte[] second = device.encrypt(plaintext);

        assertFalse(Arrays.equals(first, second));
    }

    @Test
    public void outOfOrderMessageFailsToDecrypt() {
        SessionKeys shared = keys();
        SessionCrypto device = SessionCrypto.forDevice(shared);
        SessionCrypto reader = SessionCrypto.forReader(shared);
        byte[] first = device.encrypt("one".getBytes(StandardCharsets.UTF_8));
        device.encrypt("two".getBytes(StandardCharsets.UTF_8));

        reader.decrypt(first);

        assertThrows(ProximityException.class,
                () -> reader.decrypt(first));
    }

    @Test
    public void establishmentCarriesReaderKeyAndData() {
        CBORObject readerKey = CoseKeys.toCoseKey(
                (ECPublicKey) CoseKeys.generateEphemeral().getPublic());
        CBORObject establishment = CBORObject.NewMap();
        establishment.Add("eReaderKey",
                CBORObject.FromObject(readerKey.EncodeToBytes()).WithTag(24));
        establishment.Add("data", new byte[]{1, 2, 3});

        SessionMessages.Establishment parsed =
                SessionMessages.parseEstablishment(establishment.EncodeToBytes());

        assertArrayEquals(readerKey.EncodeToBytes(), parsed.eReaderKeyBytes);
        assertArrayEquals(new byte[]{1, 2, 3}, parsed.data);
    }

    @Test
    public void sessionDataRoundTrips() {
        byte[] encoded = SessionMessages.data(new byte[]{9, 8, 7});

        assertArrayEquals(new byte[]{9, 8, 7}, SessionMessages.parseData(encoded));
    }

    @Test
    public void terminationCarriesStatusTwenty() {
        CBORObject termination = CBORObject.DecodeFromBytes(SessionMessages.termination());

        assertEquals(20, termination.get("status").AsInt32Value());
    }

    @Test
    public void destroyZeroesBothKeyArrays() throws Exception {
        SessionCrypto device = SessionCrypto.forDevice(keys());
        device.encrypt("warm up".getBytes(StandardCharsets.UTF_8));

        device.destroy();

        assertArrayEquals(new byte[32], keyField(device, "encryptKey"));
        assertArrayEquals(new byte[32], keyField(device, "decryptKey"));
    }

    @Test
    public void destroyIsSafeToCallTwice() {
        SessionCrypto device = SessionCrypto.forDevice(keys());

        device.destroy();
        device.destroy();
    }

    private static byte[] keyField(SessionCrypto crypto, String name) throws Exception {
        Field field = SessionCrypto.class.getDeclaredField(name);
        field.setAccessible(true);
        return (byte[]) field.get(crypto);
    }
}
