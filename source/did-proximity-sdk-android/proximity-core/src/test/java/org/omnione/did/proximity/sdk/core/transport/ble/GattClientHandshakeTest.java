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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class GattClientHandshakeTest {

    private final GattClientHandshake handshake = new GattClientHandshake(23);

    @Test
    public void theFirstActionIsToConnect() {
        assertEquals(GattClientHandshake.Action.CONNECT, handshake.onDeviceFound());
    }

    @Test
    public void connectingLeadsToServiceDiscovery() {
        handshake.onDeviceFound();

        assertEquals(GattClientHandshake.Action.DISCOVER_SERVICES, handshake.onConnected());
    }

    @Test
    public void discoveryLeadsToSubscribingToState() {
        handshake.onDeviceFound();
        handshake.onConnected();

        assertEquals(GattClientHandshake.Action.SUBSCRIBE_STATE, handshake.onServicesDiscovered());
    }

    @Test
    public void theSecondSubscriptionIsServerToClient() {
        drive();

        assertEquals(GattClientHandshake.Action.SUBSCRIBE_SERVER_TO_CLIENT,
                handshake.onDescriptorWritten());
    }

    @Test
    public void bothSubscriptionsLeadToAnMtuRequest() {
        drive();
        handshake.onDescriptorWritten();

        assertEquals(GattClientHandshake.Action.REQUEST_MTU, handshake.onDescriptorWritten());
    }

    @Test
    public void theMtuResultLeadsToTheStartWrite() {
        drive();
        handshake.onDescriptorWritten();
        handshake.onDescriptorWritten();

        assertEquals(GattClientHandshake.Action.WRITE_START, handshake.onMtuChanged(515));
        assertEquals(515, handshake.mtu());
    }

    @Test
    public void theStartWriteCompletesTheHandshake() {
        completeHandshake();

        assertTrue(handshake.isStarted());
    }

    @Test
    public void aLostConnectionBeforeStartMayReconnect() {
        drive();

        assertEquals(GattClientHandshake.Action.RECONNECT, handshake.onConnectionLost());
    }

    @Test
    public void aLostConnectionAfterStartAbortsTheTransaction() {
        completeHandshake();

        assertEquals(GattClientHandshake.Action.ABORT, handshake.onConnectionLost());
    }

    @Test
    public void anUnstartedHandshakeReportsNotStarted() {
        drive();

        assertFalse(handshake.isStarted());
    }

    @Test
    public void theMtuFallsBackToTheDefaultWhenNegotiationFails() {
        drive();
        handshake.onDescriptorWritten();
        handshake.onDescriptorWritten();

        assertEquals(GattClientHandshake.Action.WRITE_START, handshake.onMtuChanged(0));
        assertEquals(23, handshake.mtu());
    }

    @Test
    public void onMtuChangedBeforeSubscriptionsCompleteDoesNotChangeMtu() {
        handshake.onDeviceFound();
        handshake.onConnected();
        handshake.onServicesDiscovered();

        assertEquals(GattClientHandshake.Action.NONE, handshake.onMtuChanged(515));
        assertEquals(23, handshake.mtu());
    }

    @Test
    public void onMtuChangedAfterStartDoesNotChangeMtu() {
        completeHandshake();

        assertEquals(GattClientHandshake.Action.NONE, handshake.onMtuChanged(515));
        assertEquals(515, handshake.mtu());
    }

    @Test
    public void staleMtuAfterReconnect() {
        completeHandshake();
        assertEquals(515, handshake.mtu());

        handshake.onConnectionLost();
        assertEquals(23, handshake.mtu());

        handshake.onDeviceFound();
        handshake.onConnected();
        handshake.onServicesDiscovered();
        handshake.onDescriptorWritten();
        handshake.onDescriptorWritten();

        assertEquals(GattClientHandshake.Action.WRITE_START, handshake.onMtuChanged(0));
        assertEquals(23, handshake.mtu());
    }

    @Test
    public void onConnectedWithoutDeviceFoundReturnsNone() {
        assertEquals(GattClientHandshake.Action.NONE, handshake.onConnected());
    }

    @Test
    public void onDescriptorWrittenBeforeDiscoveryReturnsNone() {
        handshake.onDeviceFound();
        handshake.onConnected();

        assertEquals(GattClientHandshake.Action.NONE, handshake.onDescriptorWritten());
    }

    @Test
    public void duplicateOnDeviceFoundReturnsNone() {
        handshake.onDeviceFound();

        assertEquals(GattClientHandshake.Action.NONE, handshake.onDeviceFound());
    }

    @Test
    public void onStartWrittenOutOfOrderKeepsNotStarted() {
        handshake.onStartWritten();

        assertFalse(handshake.isStarted());
    }

    @Test
    public void onConnectionLostWhileScanningReturnsNone() {
        assertEquals(GattClientHandshake.Action.NONE, handshake.onConnectionLost());
    }

    @Test
    public void anIdentProfileReadsIdentBeforeSubscribing() {
        GattClientHandshake ident = new GattClientHandshake(23, true);
        ident.onDeviceFound();
        ident.onConnected();

        assertEquals(GattClientHandshake.Action.READ_IDENT, ident.onServicesDiscovered());
    }

    @Test
    public void theIdentReadLeadsToTheStateSubscription() {
        GattClientHandshake ident = new GattClientHandshake(23, true);
        ident.onDeviceFound();
        ident.onConnected();
        ident.onServicesDiscovered();

        assertEquals(GattClientHandshake.Action.SUBSCRIBE_STATE, ident.onIdentRead());
    }

    @Test
    public void aProfileWithoutIdentSkipsTheIdentRead() {
        drive();

        assertEquals(GattClientHandshake.Action.NONE, handshake.onIdentRead());
        assertEquals(GattClientHandshake.Action.SUBSCRIBE_SERVER_TO_CLIENT,
                handshake.onDescriptorWritten());
    }

    @Test
    public void onIdentReadBeforeDiscoveryReturnsNone() {
        GattClientHandshake ident = new GattClientHandshake(23, true);
        ident.onDeviceFound();
        ident.onConnected();

        assertEquals(GattClientHandshake.Action.NONE, ident.onIdentRead());
    }

    @Test
    public void aDuplicateIdentReadReturnsNone() {
        GattClientHandshake ident = new GattClientHandshake(23, true);
        ident.onDeviceFound();
        ident.onConnected();
        ident.onServicesDiscovered();
        ident.onIdentRead();

        assertEquals(GattClientHandshake.Action.NONE, ident.onIdentRead());
    }

    private void drive() {
        handshake.onDeviceFound();
        handshake.onConnected();
        handshake.onServicesDiscovered();
    }

    private void completeHandshake() {
        drive();
        handshake.onDescriptorWritten();
        handshake.onDescriptorWritten();
        handshake.onMtuChanged(515);
        handshake.onStartWritten();
    }

    @Test
    public void anEngagementPsmSkipsGattAndConnectsL2capDirectly() {
        assertEquals(GattClientHandshake.Action.CONNECT_L2CAP, handshake.onDeviceFoundWithPsm());
        assertFalse(handshake.isStarted());

        assertEquals(GattClientHandshake.Action.NONE, handshake.onL2capConnected());
        assertTrue(handshake.isStarted());
    }

    @Test
    public void aFailedDirectL2capConnectFallsBackToGattOnce() {
        handshake.onDeviceFoundWithPsm();

        assertEquals(GattClientHandshake.Action.CONNECT, handshake.onL2capFailed());
        assertEquals(GattClientHandshake.Action.DISCOVER_SERVICES, handshake.onConnected());
        assertEquals("the characteristic is not tried again after a failed direct connect",
                GattClientHandshake.Action.SUBSCRIBE_STATE, handshake.onServicesDiscovered(true));
    }

    @Test
    public void aRescanAfterAFailedL2capAttemptConnectsGatt() {
        handshake.onDeviceFoundWithPsm();
        handshake.onL2capFailed();
        handshake.onConnectionLost();

        assertEquals(GattClientHandshake.Action.CONNECT, handshake.onDeviceFoundWithPsm());
    }

    @Test
    public void anL2capCharacteristicLeadsToReadingThePsm() {
        handshake.onDeviceFound();
        handshake.onConnected();

        assertEquals(GattClientHandshake.Action.READ_PSM, handshake.onServicesDiscovered(true));
        assertEquals(GattClientHandshake.Action.CONNECT_L2CAP, handshake.onPsmRead(192));
        handshake.onL2capConnected();
        assertTrue(handshake.isStarted());
    }

    @Test
    public void withoutAnL2capCharacteristicTheGattPathIsUnchanged() {
        handshake.onDeviceFound();
        handshake.onConnected();

        assertEquals(GattClientHandshake.Action.SUBSCRIBE_STATE,
                handshake.onServicesDiscovered(false));
    }

    @Test
    public void anUnreadablePsmFallsBackToGatt() {
        handshake.onDeviceFound();
        handshake.onConnected();
        handshake.onServicesDiscovered(true);

        assertEquals(GattClientHandshake.Action.SUBSCRIBE_STATE, handshake.onPsmRead(null));
        assertEquals(GattClientHandshake.Action.SUBSCRIBE_SERVER_TO_CLIENT,
                handshake.onDescriptorWritten());
    }

    @Test
    public void aFailedL2capConnectOverAnOpenGattLinkFallsBackToSubscribing() {
        handshake.onDeviceFound();
        handshake.onConnected();
        handshake.onServicesDiscovered(true);
        handshake.onPsmRead(192);

        assertEquals(GattClientHandshake.Action.SUBSCRIBE_STATE, handshake.onL2capFailed());
    }

    @Test
    public void onAnIdentProfileThePsmIsReadAfterTheIdent() {
        GattClientHandshake verifying = new GattClientHandshake(23, true);
        verifying.onDeviceFound();
        verifying.onConnected();

        assertEquals(GattClientHandshake.Action.READ_IDENT, verifying.onServicesDiscovered(true));
        assertEquals(GattClientHandshake.Action.READ_PSM, verifying.onIdentRead());
    }

    @Test
    public void l2capCallbacksOutOfStageAreIgnored() {
        assertEquals(GattClientHandshake.Action.NONE, handshake.onPsmRead(192));
        assertEquals(GattClientHandshake.Action.NONE, handshake.onL2capConnected());
        assertEquals(GattClientHandshake.Action.NONE, handshake.onL2capFailed());
        assertFalse(handshake.isStarted());
    }

    @Test
    public void aConnectionLossAfterL2capStartedIsAnAbort() {
        handshake.onDeviceFoundWithPsm();
        handshake.onL2capConnected();

        assertEquals(GattClientHandshake.Action.ABORT, handshake.onConnectionLost());
    }
}
