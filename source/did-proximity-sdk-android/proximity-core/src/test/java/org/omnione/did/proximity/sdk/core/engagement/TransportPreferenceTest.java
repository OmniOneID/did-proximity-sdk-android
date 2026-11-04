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
import static org.junit.Assert.assertThrows;

import org.junit.Test;
import org.omnione.did.proximity.sdk.core.ProximityException;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class TransportPreferenceTest {

    @Test
    public void orderIsKeptAsGiven() {
        TransportPreference preference = TransportPreference.of(
                TransportMode.WIFI_AWARE, TransportMode.BLE_PERIPHERAL_SERVER);

        assertEquals(Arrays.asList(TransportMode.WIFI_AWARE, TransportMode.BLE_PERIPHERAL_SERVER),
                preference.order());
    }

    @Test
    public void duplicatesAreCollapsedToTheirFirstPosition() {
        TransportPreference preference = TransportPreference.of(Arrays.asList(
                TransportMode.BLE_CENTRAL_CLIENT, TransportMode.WIFI_AWARE,
                TransportMode.BLE_CENTRAL_CLIENT));

        assertEquals(Arrays.asList(TransportMode.BLE_CENTRAL_CLIENT, TransportMode.WIFI_AWARE),
                preference.order());
    }

    @Test
    public void anEmptyPreferenceIsRejected() {
        assertThrows(ProximityException.class,
                () -> TransportPreference.of(Collections.emptyList()));
    }

    @Test
    public void fromModePreferenceKeepsTheBleOrderAndNothingElse() {
        assertEquals(Arrays.asList(TransportMode.BLE_PERIPHERAL_SERVER,
                        TransportMode.BLE_CENTRAL_CLIENT),
                TransportPreference.from(ModePreference.PERIPHERAL_SERVER_FIRST).order());
        assertEquals(Arrays.asList(TransportMode.BLE_CENTRAL_CLIENT,
                        TransportMode.BLE_PERIPHERAL_SERVER),
                TransportPreference.from(ModePreference.CENTRAL_CLIENT_FIRST).order());
    }

    @Test
    public void withoutDropsOneModeAndKeepsTheRestInOrder() {
        TransportPreference preference = TransportPreference.of(TransportMode.WIFI_AWARE,
                TransportMode.BLE_CENTRAL_CLIENT, TransportMode.BLE_PERIPHERAL_SERVER);

        List<TransportMode> order = preference.without(TransportMode.WIFI_AWARE).order();

        assertEquals(Arrays.asList(TransportMode.BLE_CENTRAL_CLIENT,
                TransportMode.BLE_PERIPHERAL_SERVER), order);
    }

    @Test
    public void withoutTheLastModeIsRejected() {
        assertThrows(ProximityException.class,
                () -> TransportPreference.of(TransportMode.WIFI_AWARE)
                        .without(TransportMode.WIFI_AWARE));
    }

    @Test
    public void modePreferenceExposesItsTransports() {
        assertEquals(TransportPreference.from(ModePreference.PERIPHERAL_SERVER_FIRST).order(),
                ModePreference.PERIPHERAL_SERVER_FIRST.transports());
    }

    @Test
    public void orderIsImmutable() {
        TransportPreference preference = TransportPreference.of(TransportMode.WIFI_AWARE);

        assertThrows(UnsupportedOperationException.class,
                () -> preference.order().add(TransportMode.BLE_CENTRAL_CLIENT));
    }
}
