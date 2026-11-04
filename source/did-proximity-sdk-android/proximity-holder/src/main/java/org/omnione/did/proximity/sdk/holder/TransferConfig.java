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

package org.omnione.did.proximity.sdk.holder;

import androidx.annotation.NonNull;

import org.omnione.did.proximity.sdk.core.engagement.BleTransferMode;

public final class TransferConfig {

    private final BleTransferMode bleMode;
    private final boolean wifiAware;

    private TransferConfig(@NonNull BleTransferMode bleMode, boolean wifiAware) {
        this.bleMode = bleMode;
        this.wifiAware = wifiAware;
    }

    @NonNull
    public static TransferConfig ble(@NonNull BleTransferMode bleMode) {
        return new TransferConfig(bleMode, false);
    }

    @NonNull
    public TransferConfig withWifiAware(boolean enabled) {
        return new TransferConfig(bleMode, enabled);
    }

    @NonNull
    public BleTransferMode bleMode() {
        return bleMode;
    }

    public boolean wifiAware() {
        return wifiAware;
    }
}
