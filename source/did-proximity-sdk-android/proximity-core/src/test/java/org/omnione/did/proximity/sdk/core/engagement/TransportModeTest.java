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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class TransportModeTest {

    @Test
    public void everyBleModeMapsToItsOwnTransportMode() {
        assertEquals(TransportMode.BLE_CENTRAL_CLIENT, TransportMode.of(BleMode.CENTRAL_CLIENT));
        assertEquals(TransportMode.BLE_PERIPHERAL_SERVER,
                TransportMode.of(BleMode.PERIPHERAL_SERVER));
    }

    @Test
    public void bleTransportModesRoundTripToTheirBleMode() {
        assertEquals(BleMode.CENTRAL_CLIENT, TransportMode.BLE_CENTRAL_CLIENT.ble());
        assertEquals(BleMode.PERIPHERAL_SERVER, TransportMode.BLE_PERIPHERAL_SERVER.ble());
    }

    @Test
    public void wifiAwareHasNoBleMode() {
        assertNull(TransportMode.WIFI_AWARE.ble());
    }

    @Test
    public void nfcHasNoBleMode() {
        assertNull(TransportMode.NFC.ble());
    }
}
