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

import androidx.annotation.NonNull;

import org.omnione.did.proximity.sdk.core.ProximityException;
import org.omnione.did.proximity.sdk.core.crypto.Crypto;
import org.omnione.did.proximity.sdk.core.crypto.Hkdf;
import org.omnione.did.proximity.sdk.core.engagement.SessionTranscripts;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.interfaces.ECPublicKey;
import java.util.Arrays;

import javax.crypto.KeyAgreement;

public final class SessionKeys {

    private static final int KEY_LENGTH = 32;
    private static final byte[] INFO_DEVICE = "SKDevice".getBytes(StandardCharsets.UTF_8);
    private static final byte[] INFO_READER = "SKReader".getBytes(StandardCharsets.UTF_8);

    private final byte[] deviceKey;
    private final byte[] readerKey;

    private SessionKeys(@NonNull byte[] deviceKey, @NonNull byte[] readerKey) {
        this.deviceKey = deviceKey;
        this.readerKey = readerKey;
    }

    @NonNull
    public static SessionKeys derive(@NonNull PrivateKey selfPrivate,
                                     @NonNull ECPublicKey remotePublic,
                                     @NonNull byte[] sessionTranscript) {
        byte[] sharedSecret = agree(selfPrivate, remotePublic);
        byte[] salt = sha256(SessionTranscripts.taggedBytes(sessionTranscript));
        byte[] prk = Hkdf.extract(salt, sharedSecret);
        Arrays.fill(sharedSecret, (byte) 0x00);
        try {
            return new SessionKeys(
                    Hkdf.expand(prk, INFO_DEVICE, KEY_LENGTH),
                    Hkdf.expand(prk, INFO_READER, KEY_LENGTH));
        } finally {
            Arrays.fill(prk, (byte) 0x00);
        }
    }

    @NonNull
    public byte[] deviceKey() {
        return deviceKey.clone();
    }

    @NonNull
    public byte[] readerKey() {
        return readerKey.clone();
    }

    @NonNull
    private static byte[] agree(@NonNull PrivateKey selfPrivate, @NonNull ECPublicKey remotePublic) {
        try {
            KeyAgreement agreement = KeyAgreement.getInstance("ECDH", Crypto.provider());
            agreement.init(selfPrivate);
            agreement.doPhase(remotePublic, true);
            return agreement.generateSecret();
        } catch (Exception e) {
            throw new ProximityException("ECDH key agreement failed", e);
        }
    }

    @NonNull
    private static byte[] sha256(@NonNull byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256", Crypto.provider()).digest(data);
        } catch (GeneralSecurityException e) {
            throw new ProximityException("SHA-256 unavailable", e);
        }
    }
}
