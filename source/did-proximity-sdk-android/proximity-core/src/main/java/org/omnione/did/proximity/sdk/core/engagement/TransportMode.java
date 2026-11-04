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

package org.omnione.did.proximity.sdk.core.engagement;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public enum TransportMode {
    BLE_CENTRAL_CLIENT(BleMode.CENTRAL_CLIENT),
    BLE_PERIPHERAL_SERVER(BleMode.PERIPHERAL_SERVER),
    WIFI_AWARE(null),
    NFC(null);

    private final BleMode ble;

    TransportMode(@Nullable BleMode ble) {
        this.ble = ble;
    }

    @NonNull
    public static TransportMode of(@NonNull BleMode mode) {
        return mode == BleMode.CENTRAL_CLIENT ? BLE_CENTRAL_CLIENT : BLE_PERIPHERAL_SERVER;
    }

    @Nullable
    public BleMode ble() {
        return ble;
    }
}
