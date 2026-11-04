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

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public final class SessionCrypto {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final String ALGORITHM = "AES";
    private static final int TAG_LENGTH_BITS = 128;
    private static final int IV_LENGTH = 12;

    private static final int IDENTIFIER_DEVICE = 1;
    private static final int IDENTIFIER_READER = 0;

    private final byte[] encryptKey;
    private final byte[] decryptKey;
    private final int encryptIdentifier;
    private final int decryptIdentifier;

    private int encryptCounter = 1;
    private int decryptCounter = 1;

    private SessionCrypto(@NonNull byte[] encryptKey, @NonNull byte[] decryptKey,
                          int encryptIdentifier, int decryptIdentifier) {
        this.encryptKey = encryptKey;
        this.decryptKey = decryptKey;
        this.encryptIdentifier = encryptIdentifier;
        this.decryptIdentifier = decryptIdentifier;
    }

    @NonNull
    public static SessionCrypto forDevice(@NonNull SessionKeys keys) {
        return new SessionCrypto(keys.deviceKey(), keys.readerKey(),
                IDENTIFIER_DEVICE, IDENTIFIER_READER);
    }

    @NonNull
    public static SessionCrypto forReader(@NonNull SessionKeys keys) {
        return new SessionCrypto(keys.readerKey(), keys.deviceKey(),
                IDENTIFIER_READER, IDENTIFIER_DEVICE);
    }

    @NonNull
    public synchronized byte[] encrypt(@NonNull byte[] plaintext) {
        byte[] result = run(Cipher.ENCRYPT_MODE, encryptKey,
                iv(encryptIdentifier, encryptCounter), plaintext);
        encryptCounter++;
        return result;
    }

    @NonNull
    public synchronized byte[] decrypt(@NonNull byte[] ciphertext) {
        byte[] result = run(Cipher.DECRYPT_MODE, decryptKey,
                iv(decryptIdentifier, decryptCounter), ciphertext);
        decryptCounter++;
        return result;
    }

    public synchronized void destroy() {
        Arrays.fill(encryptKey, (byte) 0x00);
        Arrays.fill(decryptKey, (byte) 0x00);
    }

    @NonNull
    private static byte[] iv(int identifier, int counter) {
        return ByteBuffer.allocate(IV_LENGTH)
                .putInt(0)
                .putInt(identifier)
                .putInt(counter)
                .array();
    }

    @NonNull
    private static byte[] run(int mode, @NonNull byte[] key, @NonNull byte[] iv,
                              @NonNull byte[] input) {
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION, Crypto.provider());
            cipher.init(mode, new SecretKeySpec(key, ALGORITHM),
                    new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            return cipher.doFinal(input);
        } catch (GeneralSecurityException e) {
            throw new ProximityException("AES-256-GCM " + (mode == Cipher.ENCRYPT_MODE
                    ? "encryption" : "decryption") + " failed", e);
        }
    }
}
