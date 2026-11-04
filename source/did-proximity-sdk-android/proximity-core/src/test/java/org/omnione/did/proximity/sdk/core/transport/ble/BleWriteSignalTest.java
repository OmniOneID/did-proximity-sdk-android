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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.util.UUID;

public class BleWriteSignalTest {

    @Test
    public void stateStartIsASessionStart() {
        assertEquals(BleWriteSignal.SESSION_START,
                BleWriteSignal.of(GattProfile.MDOC, BleUuids.STATE,
                        new byte[]{BleUuids.STATE_START}));
    }

    @Test
    public void stateEndIsASessionEnd() {
        assertEquals(BleWriteSignal.SESSION_END,
                BleWriteSignal.of(GattProfile.MDOC, BleUuids.STATE,
                        new byte[]{BleUuids.STATE_END}));
    }

    @Test
    public void anUnknownStateValueIsIgnored() {
        assertEquals(BleWriteSignal.IGNORED,
                BleWriteSignal.of(GattProfile.MDOC, BleUuids.STATE, new byte[]{0x7F}));
    }

    @Test
    public void anEmptyStateWriteIsIgnored() {
        assertEquals(BleWriteSignal.IGNORED,
                BleWriteSignal.of(GattProfile.MDOC, BleUuids.STATE, new byte[0]));
    }

    @Test
    public void clientToServerCarriesDataChunks() {
        assertEquals(BleWriteSignal.DATA_CHUNK,
                BleWriteSignal.of(GattProfile.MDOC, BleUuids.CLIENT_TO_SERVER,
                        new byte[]{0x00, 0x01}));
    }

    @Test
    public void anEmptyClientToServerWriteIsIgnored() {
        assertEquals(BleWriteSignal.IGNORED,
                BleWriteSignal.of(GattProfile.MDOC, BleUuids.CLIENT_TO_SERVER, new byte[0]));
    }

    @Test
    public void aWriteToAnUnrelatedCharacteristicIsIgnored() {
        assertEquals(BleWriteSignal.IGNORED,
                BleWriteSignal.of(GattProfile.MDOC,
                        UUID.fromString("0000ffff-0000-1000-8000-00805f9b34fb"),
                        new byte[]{BleUuids.STATE_START}));
    }

    @Test
    public void theReaderProfileUsesItsOwnStateCharacteristic() {
        assertEquals(BleWriteSignal.SESSION_START,
                BleWriteSignal.of(GattProfile.MDOC_READER, BleUuids.READER_STATE,
                        new byte[]{BleUuids.STATE_START}));
        assertEquals(BleWriteSignal.DATA_CHUNK,
                BleWriteSignal.of(GattProfile.MDOC_READER, BleUuids.READER_CLIENT_TO_SERVER,
                        new byte[]{0x00, 0x01}));
    }

    @Test
    public void theProfilesDoNotAcceptEachOthersCharacteristics() {
        assertEquals(BleWriteSignal.IGNORED,
                BleWriteSignal.of(GattProfile.MDOC, BleUuids.READER_STATE,
                        new byte[]{BleUuids.STATE_START}));
        assertEquals(BleWriteSignal.IGNORED,
                BleWriteSignal.of(GattProfile.MDOC_READER, BleUuids.STATE,
                        new byte[]{BleUuids.STATE_START}));
    }

    @Test
    public void onlyTheReaderProfileCarriesAnIdentCharacteristic() {
        assertNull(GattProfile.MDOC.ident());
        assertNotNull(GattProfile.MDOC_READER.ident());
    }

    @Test
    public void eachProfileCarriesItsOwnServerToClientCharacteristic() {
        assertEquals(BleUuids.SERVER_TO_CLIENT, GattProfile.MDOC.serverToClient());
        assertEquals(BleUuids.READER_SERVER_TO_CLIENT, GattProfile.MDOC_READER.serverToClient());
    }
}
