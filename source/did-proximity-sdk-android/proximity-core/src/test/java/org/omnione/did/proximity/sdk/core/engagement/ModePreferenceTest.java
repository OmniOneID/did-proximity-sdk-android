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

import java.util.EnumSet;
import java.util.Set;

public class ModePreferenceTest {

    private static final Set<BleMode> BOTH =
            EnumSet.of(BleMode.CENTRAL_CLIENT, BleMode.PERIPHERAL_SERVER);

    @Test
    public void preferenceDecidesWhenBothModesAreAdvertised() {
        assertEquals(BleMode.CENTRAL_CLIENT,
                ModePreference.CENTRAL_CLIENT_FIRST.preferred(BOTH));
        assertEquals(BleMode.PERIPHERAL_SERVER,
                ModePreference.PERIPHERAL_SERVER_FIRST.preferred(BOTH));
    }

    @Test
    public void aSingleAdvertisedModeWinsRegardlessOfThePreference() {
        Set<BleMode> centralOnly = EnumSet.of(BleMode.CENTRAL_CLIENT);
        Set<BleMode> peripheralOnly = EnumSet.of(BleMode.PERIPHERAL_SERVER);

        assertEquals(BleMode.CENTRAL_CLIENT,
                ModePreference.CENTRAL_CLIENT_FIRST.preferred(centralOnly));
        assertEquals(BleMode.CENTRAL_CLIENT,
                ModePreference.PERIPHERAL_SERVER_FIRST.preferred(centralOnly));
        assertEquals(BleMode.PERIPHERAL_SERVER,
                ModePreference.CENTRAL_CLIENT_FIRST.preferred(peripheralOnly));
        assertEquals(BleMode.PERIPHERAL_SERVER,
                ModePreference.PERIPHERAL_SERVER_FIRST.preferred(peripheralOnly));
    }

    @Test
    public void anEmptyModeSetIsRejected() {
        assertThrows(ProximityException.class,
                () -> ModePreference.CENTRAL_CLIENT_FIRST.preferred(EnumSet.noneOf(BleMode.class)));
    }
}
