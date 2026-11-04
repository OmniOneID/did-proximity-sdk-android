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

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class BleTransferModeTest {

    @Test
    public void bothCarriesCentralClientFirst() {
        List<BleMode> ordered = new ArrayList<>(BleTransferMode.BOTH.modes());

        assertEquals(Arrays.asList(BleMode.CENTRAL_CLIENT, BleMode.PERIPHERAL_SERVER), ordered);
    }

    @Test
    public void singleModesCarryOnlyThemselves() {
        assertEquals(1, BleTransferMode.CENTRAL_CLIENT.modes().size());
        assertEquals(BleMode.CENTRAL_CLIENT,
                BleTransferMode.CENTRAL_CLIENT.modes().iterator().next());
        assertEquals(1, BleTransferMode.PERIPHERAL_SERVER.modes().size());
        assertEquals(BleMode.PERIPHERAL_SERVER,
                BleTransferMode.PERIPHERAL_SERVER.modes().iterator().next());
    }

    @Test
    public void everySingleBleModeMapsBackToATransferMode() {
        for (BleMode mode : BleMode.values()) {
            assertEquals(1, BleTransferMode.of(mode).modes().size());
            assertEquals(mode, BleTransferMode.of(mode).modes().iterator().next());
        }
    }

    @Test
    public void theReturnedSetIsIndependentOfTheEnum() {
        BleTransferMode.BOTH.modes().clear();

        assertEquals(2, BleTransferMode.BOTH.modes().size());
    }
}
