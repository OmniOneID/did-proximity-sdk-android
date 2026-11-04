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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

import com.upokecenter.cbor.CBORObject;

import org.junit.Test;
import org.omnione.did.proximity.sdk.core.ProximityException;
import org.omnione.did.proximity.sdk.core.cbor.CoseKeys;
import org.omnione.did.proximity.sdk.core.engagement.SessionTranscripts;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.interfaces.ECPublicKey;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public class SessionCryptoWireFormatTest {

    private static final byte[] IV_DEVICE_FIRST =
            {0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 1};
    private static final byte[] IV_DEVICE_SECOND =
            {0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 2};
    private static final byte[] IV_READER_FIRST =
            {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1};

    private static final byte[] TRANSCRIPT =
            SessionTranscripts.forQr(new byte[]{(byte) 0xA0}, new byte[]{(byte) 0xA1});

    private static SessionKeys keys() {
        KeyPair device = CoseKeys.generateEphemeral();
        KeyPair reader = CoseKeys.generateEphemeral();
        return SessionKeys.derive(device.getPrivate(), (ECPublicKey) reader.getPublic(), TRANSCRIPT);
    }

    private static byte[] decryptWithPlatformProvider(byte[] key, byte[] iv, byte[] ciphertext)
            throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                new GCMParameterSpec(128, iv));
        return cipher.doFinal(ciphertext);
    }

    @Test
    public void deviceEncryptsWithDeviceKeyAndIdentifierOne() throws Exception {
        SessionKeys shared = keys();
        byte[] plaintext = "device response".getBytes(StandardCharsets.UTF_8);

        byte[] ciphertext = SessionCrypto.forDevice(shared).encrypt(plaintext);

        assertArrayEquals(plaintext, decryptWithPlatformProvider(
                shared.deviceKey(), IV_DEVICE_FIRST, ciphertext));
    }

    @Test
    public void readerEncryptsWithReaderKeyAndIdentifierZero() throws Exception {
        SessionKeys shared = keys();
        byte[] plaintext = "device request".getBytes(StandardCharsets.UTF_8);

        byte[] ciphertext = SessionCrypto.forReader(shared).encrypt(plaintext);

        assertArrayEquals(plaintext, decryptWithPlatformProvider(
                shared.readerKey(), IV_READER_FIRST, ciphertext));
    }

    @Test
    public void secondMessageAdvancesCounterToTwo() throws Exception {
        SessionKeys shared = keys();
        SessionCrypto device = SessionCrypto.forDevice(shared);
        byte[] plaintext = "second".getBytes(StandardCharsets.UTF_8);

        device.encrypt("first".getBytes(StandardCharsets.UTF_8));
        byte[] ciphertext = device.encrypt(plaintext);

        assertArrayEquals(plaintext, decryptWithPlatformProvider(
                shared.deviceKey(), IV_DEVICE_SECOND, ciphertext));
    }

    @Test
    public void failedDecryptLeavesReceiveCounterUnchanged() {
        SessionKeys shared = keys();
        SessionCrypto device = SessionCrypto.forDevice(shared);
        SessionCrypto reader = SessionCrypto.forReader(shared);
        byte[] plaintext = "first".getBytes(StandardCharsets.UTF_8);
        byte[] ciphertext = device.encrypt(plaintext);

        assertThrows(ProximityException.class,
                () -> reader.decrypt(new byte[ciphertext.length]));

        assertArrayEquals(plaintext, reader.decrypt(ciphertext));
    }

    @Test
    public void establishmentRejectsMissingFields() {
        CBORObject withoutData = CBORObject.NewMap();
        withoutData.Add("eReaderKey", CBORObject.FromObject(new byte[]{1}).WithTag(24));

        CBORObject withoutReaderKey = CBORObject.NewMap();
        withoutReaderKey.Add("data", new byte[]{1});

        assertThrows(ProximityException.class,
                () -> SessionMessages.parseEstablishment(withoutData.EncodeToBytes()));
        assertThrows(ProximityException.class,
                () -> SessionMessages.parseEstablishment(withoutReaderKey.EncodeToBytes()));
    }

    @Test
    public void establishmentRejectsReaderKeyWithWrongTagOrNoTag() {
        CBORObject wrongTag = CBORObject.NewMap();
        wrongTag.Add("eReaderKey", CBORObject.FromObject(new byte[]{1}).WithTag(25));
        wrongTag.Add("data", new byte[]{1});

        CBORObject noTag = CBORObject.NewMap();
        noTag.Add("eReaderKey", new byte[]{1});
        noTag.Add("data", new byte[]{1});

        assertThrows(ProximityException.class,
                () -> SessionMessages.parseEstablishment(wrongTag.EncodeToBytes()));
        assertThrows(ProximityException.class,
                () -> SessionMessages.parseEstablishment(noTag.EncodeToBytes()));
    }

    @Test
    public void nonMapFramesAreRejected() {
        byte[] integerFrame = CBORObject.FromObject(42).EncodeToBytes();
        byte[] arrayFrame = CBORObject.NewArray().EncodeToBytes();

        assertThrows(ProximityException.class,
                () -> SessionMessages.parseEstablishment(integerFrame));
        assertThrows(ProximityException.class,
                () -> SessionMessages.parseData(integerFrame));
        assertThrows(ProximityException.class,
                () -> SessionMessages.parseEstablishment(arrayFrame));
        assertThrows(ProximityException.class,
                () -> SessionMessages.parseData(arrayFrame));
    }

    @Test
    public void textStringDataIsRejected() {
        CBORObject establishment = CBORObject.NewMap();
        establishment.Add("eReaderKey", CBORObject.FromObject(new byte[]{1}).WithTag(24));
        establishment.Add("data", "not bytes");

        CBORObject sessionData = CBORObject.NewMap();
        sessionData.Add("data", "not bytes");

        assertThrows(ProximityException.class,
                () -> SessionMessages.parseEstablishment(establishment.EncodeToBytes()));
        assertThrows(ProximityException.class,
                () -> SessionMessages.parseData(sessionData.EncodeToBytes()));
    }

    @Test
    public void missingDataFieldStillReturnsNull() {
        assertNull(SessionMessages.parseData(CBORObject.NewMap().EncodeToBytes()));
    }

    @Test
    public void statusIsReadBackFromATerminationFrame() {
        assertNull(SessionMessages.parseStatus(CBORObject.NewMap().EncodeToBytes()));
        assertEquals(20, SessionMessages.parseStatus(SessionMessages.termination()).intValue());
    }

    @Test
    public void nonIntegerAndOutOfRangeStatusesAreRejected() {
        CBORObject textStatus = CBORObject.NewMap();
        textStatus.Add("status", "20");

        CBORObject hugeStatus = CBORObject.NewMap();
        hugeStatus.Add("status", CBORObject.FromObject(Long.MAX_VALUE));

        CBORObject negativeStatus = CBORObject.NewMap();
        negativeStatus.Add("status", -1);

        assertThrows(ProximityException.class,
                () -> SessionMessages.parseStatus(textStatus.EncodeToBytes()));
        assertThrows(ProximityException.class,
                () -> SessionMessages.parseStatus(hugeStatus.EncodeToBytes()));
        assertThrows(ProximityException.class,
                () -> SessionMessages.parseStatus(negativeStatus.EncodeToBytes()));
    }
}
