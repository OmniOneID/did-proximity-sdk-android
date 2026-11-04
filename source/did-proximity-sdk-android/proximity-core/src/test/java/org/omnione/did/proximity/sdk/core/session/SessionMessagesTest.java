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

package org.omnione.did.proximity.sdk.core.session;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

import org.junit.Test;
import org.omnione.did.proximity.sdk.core.ProximityException;

public class SessionMessagesTest {

    private static final byte[] READER_KEY = {0x11, 0x22, 0x33};
    private static final byte[] ENCRYPTED = {0x44, 0x55};

    @Test
    public void establishmentRoundTripsThroughTheParser() {
        byte[] message = SessionMessages.establishment(READER_KEY, ENCRYPTED);

        SessionMessages.Establishment parsed = SessionMessages.parseEstablishment(message);

        assertArrayEquals(READER_KEY, parsed.eReaderKeyBytes);
        assertArrayEquals(ENCRYPTED, parsed.data);
    }

    @Test
    public void statusRoundTripsThroughTheParser() {
        assertEquals(Integer.valueOf(10),
                SessionMessages.parseStatus(SessionMessages.status(10)));
        assertEquals(Integer.valueOf(11),
                SessionMessages.parseStatus(SessionMessages.status(11)));
    }

    @Test
    public void aStatusMessageCarriesNoData() {
        assertNull(SessionMessages.parseData(SessionMessages.status(10)));
    }

    @Test
    public void terminationIsStatusTwenty() {
        assertArrayEquals(SessionMessages.status(20), SessionMessages.termination());
    }

    @Test
    public void statusRejectsANegativeValue() {
        assertThrows(ProximityException.class, () -> SessionMessages.status(-1));
    }
}
