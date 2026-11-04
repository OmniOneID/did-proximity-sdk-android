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

import org.omnione.did.proximity.sdk.core.ProximityException;

import java.util.List;
import java.util.Set;

public enum ModePreference {

    PERIPHERAL_SERVER_FIRST(BleMode.PERIPHERAL_SERVER, BleMode.CENTRAL_CLIENT),
    CENTRAL_CLIENT_FIRST(BleMode.CENTRAL_CLIENT, BleMode.PERIPHERAL_SERVER);

    private final BleMode[] order;

    ModePreference(@NonNull BleMode... order) {
        this.order = order;
    }

    @NonNull
    BleMode[] order() {
        return order.clone();
    }

    @NonNull
    public List<TransportMode> transports() {
        return TransportPreference.from(this).order();
    }

    @NonNull
    public BleMode preferred(@NonNull Set<BleMode> available) {
        for (BleMode mode : order) {
            if (available.contains(mode)) {
                return mode;
            }
        }
        throw new ProximityException("no supported BLE mode among " + available);
    }
}
