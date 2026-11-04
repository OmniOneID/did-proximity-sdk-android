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

import androidx.annotation.NonNull;

import org.omnione.did.proximity.sdk.core.crypto.Hkdf;

import java.nio.charset.StandardCharsets;

public final class BleIdent {

    private static final byte[] NO_SALT = new byte[0];
    private static final byte[] INFO = "BLEIdent".getBytes(StandardCharsets.UTF_8);
    private static final int LENGTH = 16;

    private BleIdent() {}

    @NonNull
    public static byte[] derive(@NonNull byte[] eDeviceKeyBytes) {
        return Hkdf.derive(NO_SALT, eDeviceKeyBytes, INFO, LENGTH);
    }
}
