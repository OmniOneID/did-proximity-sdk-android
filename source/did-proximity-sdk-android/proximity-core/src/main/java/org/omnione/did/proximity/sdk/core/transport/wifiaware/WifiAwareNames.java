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

package org.omnione.did.proximity.sdk.core.transport.wifiaware;

import androidx.annotation.NonNull;

import org.omnione.did.proximity.sdk.core.crypto.Hkdf;
import org.omnione.did.proximity.sdk.core.engagement.WifiOptions;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

public final class WifiAwareNames {

    private static final byte[] NO_SALT = new byte[0];
    private static final byte[] SERVICE_INFO = "NANService".getBytes(StandardCharsets.UTF_8);
    private static final byte[] PASSPHRASE_INFO =
            "NANPassphrase".getBytes(StandardCharsets.UTF_8);
    private static final int SERVICE_NAME_LENGTH = 16;
    private static final int PASSPHRASE_LENGTH = 32;
    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    private WifiAwareNames() {}

    @NonNull
    public static String serviceName(@NonNull byte[] eDeviceKeyBytes) {
        byte[] okm = Hkdf.derive(NO_SALT, eDeviceKeyBytes, SERVICE_INFO, SERVICE_NAME_LENGTH);
        char[] out = new char[okm.length * 2];
        for (int i = 0; i < okm.length; i++) {
            out[i * 2] = HEX[(okm[i] >> 4) & 0x0f];
            out[i * 2 + 1] = HEX[okm[i] & 0x0f];
        }
        return new String(out);
    }

    @NonNull
    public static String passphrase(@NonNull byte[] eDeviceKeyBytes) {
        byte[] okm = Hkdf.derive(NO_SALT, eDeviceKeyBytes, PASSPHRASE_INFO, PASSPHRASE_LENGTH);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(okm);
    }

    @NonNull
    public static String passphrase(@NonNull byte[] eDeviceKeyBytes,
                                    @NonNull WifiOptions options) {
        String advertised = options.passphrase();
        return advertised != null ? advertised : passphrase(eDeviceKeyBytes);
    }
}
