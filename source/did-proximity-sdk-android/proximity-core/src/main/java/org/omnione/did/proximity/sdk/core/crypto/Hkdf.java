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

import androidx.annotation.NonNull;

import org.omnione.did.proximity.sdk.core.ProximityException;

import java.security.GeneralSecurityException;
import java.util.Arrays;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class Hkdf {

    private static final String HMAC = "HmacSHA256";
    private static final int HASH_LENGTH = 32;
    private static final int MAX_LENGTH = 255 * HASH_LENGTH;

    private Hkdf() {}

    @NonNull
    public static byte[] extract(@NonNull byte[] salt, @NonNull byte[] ikm) {
        return hmac(salt.length == 0 ? new byte[HASH_LENGTH] : salt, ikm);
    }

    @NonNull
    public static byte[] expand(@NonNull byte[] prk, @NonNull byte[] info, int length) {
        if (length < 1 || length > MAX_LENGTH) {
            throw new ProximityException("HKDF output length is out of range: " + length);
        }
        byte[] okm = new byte[length];
        byte[] block = new byte[0];
        try {
            int offset = 0;
            for (int counter = 1; offset < length; counter++) {
                byte[] input = new byte[block.length + info.length + 1];
                System.arraycopy(block, 0, input, 0, block.length);
                System.arraycopy(info, 0, input, block.length, info.length);
                input[input.length - 1] = (byte) counter;
                block = hmac(prk, input);
                int copied = Math.min(block.length, length - offset);
                System.arraycopy(block, 0, okm, offset, copied);
                offset += copied;
            }
            return okm;
        } finally {
            Arrays.fill(block, (byte) 0x00);
        }
    }

    @NonNull
    public static byte[] derive(@NonNull byte[] salt, @NonNull byte[] ikm,
                                @NonNull byte[] info, int length) {
        byte[] prk = extract(salt, ikm);
        try {
            return expand(prk, info, length);
        } finally {
            Arrays.fill(prk, (byte) 0x00);
        }
    }

    @NonNull
    private static byte[] hmac(@NonNull byte[] key, @NonNull byte[] data) {
        try {
            Mac mac = Mac.getInstance(HMAC, Crypto.provider());
            mac.init(new SecretKeySpec(key, HMAC));
            return mac.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new ProximityException("HMAC-SHA256 failed", e);
        }
    }
}
