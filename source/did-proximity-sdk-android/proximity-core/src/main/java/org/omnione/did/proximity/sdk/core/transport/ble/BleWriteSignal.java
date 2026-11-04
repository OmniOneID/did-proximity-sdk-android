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

import java.util.UUID;

enum BleWriteSignal {

    SESSION_START,
    SESSION_END,
    DATA_CHUNK,
    IGNORED;

    @NonNull
    static BleWriteSignal of(@NonNull GattProfile profile,
                             @NonNull UUID characteristic,
                             @NonNull byte[] payload) {
        if (payload.length == 0) {
            return IGNORED;
        }
        if (profile.state().equals(characteristic)) {
            if (payload[0] == BleUuids.STATE_START) {
                return SESSION_START;
            }
            return payload[0] == BleUuids.STATE_END ? SESSION_END : IGNORED;
        }
        return profile.clientToServer().equals(characteristic) ? DATA_CHUNK : IGNORED;
    }
}
