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

package org.omnione.did.proximity.sdk.core.transport;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import androidx.annotation.NonNull;

import org.junit.Test;
import org.omnione.did.proximity.sdk.core.ProximityException;
import org.omnione.did.proximity.sdk.core.engagement.TransportMode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class TransportRaceTest {

    private static final class FakeTransport implements Transport {
        final List<byte[]> sent = new ArrayList<>();
        int started;
        Listener listener;
        int stopped;
        RuntimeException startFailure;

        @Override
        public void start(@NonNull Listener listener) {
            this.started++;
            this.listener = listener;
            if (startFailure != null) {
                throw startFailure;
            }
        }

        @Override
        public void send(@NonNull byte[] message) {
            sent.add(message);
        }

        @Override
        public void stop() {
            stopped++;
        }
    }

    private static final class RecordingListener implements Transport.Listener {
        int connected;
        final List<byte[]> messages = new ArrayList<>();
        ProximityException error;
        int errorCount;
        int disconnected;

        @Override public void onConnected() { connected++; }
        @Override public void onMessage(@NonNull byte[] message) { messages.add(message); }
        @Override public void onError(@NonNull ProximityException e) { error = e; errorCount++; }
        @Override public void onDisconnected() { disconnected++; }
    }

    private static final class DisconnectingOnStopTransport implements Transport {
        Listener listener;
        int stopped;

        @Override
        public void start(@NonNull Listener listener) {
            this.listener = listener;
        }

        @Override
        public void send(@NonNull byte[] message) {
        }

        @Override
        public void stop() {
            stopped++;
            listener.onDisconnected();
        }
    }

    private static final class SynchronouslyConnectingTransport implements Transport {
        Listener listener;

        @Override
        public void start(@NonNull Listener listener) {
            this.listener = listener;
            listener.onConnected();
        }

        @Override
        public void send(@NonNull byte[] message) {
        }

        @Override
        public void stop() {
        }
    }

    private static TransportRace raceOf(FakeTransport central, FakeTransport peripheral) {
        return TransportRace.of(Arrays.asList(
                TransportRace.Leg.of(TransportMode.BLE_CENTRAL_CLIENT, central),
                TransportRace.Leg.of(TransportMode.BLE_PERIPHERAL_SERVER, peripheral)));
    }

    @Test
    public void everyLegIsOpenedOnce() {
        FakeTransport central = new FakeTransport();
        FakeTransport peripheral = new FakeTransport();

        raceOf(central, peripheral).start(new RecordingListener());

        assertEquals(1, central.started);
        assertEquals(1, peripheral.started);
    }

    @Test
    public void theFirstLegToConnectWinsAndTheOtherIsStopped() {
        FakeTransport central = new FakeTransport();
        FakeTransport peripheral = new FakeTransport();
        RecordingListener listener = new RecordingListener();
        raceOf(central, peripheral).start(listener);

        central.listener.onConnected();

        assertEquals(1, listener.connected);
        assertEquals(1, peripheral.stopped);
        assertEquals(0, central.stopped);
    }

    @Test
    public void aSecondLegConnectingAfterTheWinnerIsNotSurfaced() {
        FakeTransport central = new FakeTransport();
        FakeTransport peripheral = new FakeTransport();
        RecordingListener listener = new RecordingListener();
        raceOf(central, peripheral).start(listener);
        central.listener.onConnected();

        peripheral.listener.onConnected();

        assertEquals(1, listener.connected);
    }

    @Test
    public void onlyTheWinnerMessagesReachTheHost() {
        FakeTransport central = new FakeTransport();
        FakeTransport peripheral = new FakeTransport();
        RecordingListener listener = new RecordingListener();
        raceOf(central, peripheral).start(listener);
        central.listener.onConnected();

        central.listener.onMessage(new byte[]{1});
        peripheral.listener.onMessage(new byte[]{2});

        assertEquals(1, listener.messages.size());
        assertArrayEquals(new byte[]{1}, listener.messages.get(0));
    }

    @Test
    public void aMessageArrivingBeforeAnyConnectionClaimsTheRace() {
        FakeTransport central = new FakeTransport();
        FakeTransport peripheral = new FakeTransport();
        RecordingListener listener = new RecordingListener();
        raceOf(central, peripheral).start(listener);

        peripheral.listener.onMessage(new byte[]{9});

        assertEquals(1, listener.messages.size());
        assertEquals(1, central.stopped);
    }

    @Test
    public void aLoserDisconnectIsSwallowed() {
        FakeTransport central = new FakeTransport();
        FakeTransport peripheral = new FakeTransport();
        RecordingListener listener = new RecordingListener();
        raceOf(central, peripheral).start(listener);
        central.listener.onConnected();

        peripheral.listener.onDisconnected();

        assertEquals(0, listener.disconnected);
    }

    @Test
    public void theWinnerDisconnectIsSurfaced() {
        FakeTransport central = new FakeTransport();
        FakeTransport peripheral = new FakeTransport();
        RecordingListener listener = new RecordingListener();
        raceOf(central, peripheral).start(listener);
        central.listener.onConnected();

        central.listener.onDisconnected();

        assertEquals(1, listener.disconnected);
    }

    @Test
    public void oneLegFailingLeavesTheRaceAlive() {
        FakeTransport central = new FakeTransport();
        FakeTransport peripheral = new FakeTransport();
        RecordingListener listener = new RecordingListener();
        raceOf(central, peripheral).start(listener);

        central.listener.onError(new ProximityException("scan permission denied"));

        assertNull(listener.error);

        peripheral.listener.onConnected();

        assertEquals(1, listener.connected);
    }

    @Test
    public void theRaceFailsOnlyWhenEveryLegHasFailed() {
        FakeTransport central = new FakeTransport();
        FakeTransport peripheral = new FakeTransport();
        RecordingListener listener = new RecordingListener();
        raceOf(central, peripheral).start(listener);

        central.listener.onError(new ProximityException("scan permission denied"));
        peripheral.listener.onError(new ProximityException("advertise unsupported"));

        assertNotNull(listener.error);
        assertTrue(listener.error.getMessage().contains("advertise unsupported"));
    }

    @Test
    public void theWinnerErrorIsSurfacedImmediately() {
        FakeTransport central = new FakeTransport();
        FakeTransport peripheral = new FakeTransport();
        RecordingListener listener = new RecordingListener();
        raceOf(central, peripheral).start(listener);
        central.listener.onConnected();

        central.listener.onError(new ProximityException("gatt failure"));

        assertNotNull(listener.error);
    }

    @Test
    public void aLegThatThrowsOnStartDoesNotBlockTheOtherLeg() {
        FakeTransport central = new FakeTransport();
        central.startFailure = new ProximityException("ble not available");
        FakeTransport peripheral = new FakeTransport();
        RecordingListener listener = new RecordingListener();

        raceOf(central, peripheral).start(listener);

        assertNull(listener.error);
        assertEquals(1, peripheral.started);
    }

    @Test
    public void everyLegThrowingOnStartSurfacesTheFailure() {
        FakeTransport central = new FakeTransport();
        central.startFailure = new ProximityException("ble not available");
        FakeTransport peripheral = new FakeTransport();
        peripheral.startFailure = new ProximityException("advertise unsupported");
        RecordingListener listener = new RecordingListener();

        raceOf(central, peripheral).start(listener);

        assertNotNull(listener.error);
    }

    @Test
    public void sendGoesToTheWinnerOnly() {
        FakeTransport central = new FakeTransport();
        FakeTransport peripheral = new FakeTransport();
        TransportRace race = raceOf(central, peripheral);
        race.start(new RecordingListener());
        central.listener.onConnected();

        race.send(new byte[]{7});

        assertEquals(1, central.sent.size());
        assertTrue(peripheral.sent.isEmpty());
    }

    @Test
    public void stopStopsEveryLeg() {
        FakeTransport central = new FakeTransport();
        FakeTransport peripheral = new FakeTransport();
        TransportRace race = raceOf(central, peripheral);
        race.start(new RecordingListener());

        race.stop();

        assertTrue(central.stopped > 0);
        assertTrue(peripheral.stopped > 0);
    }

    @Test
    public void aSingleLegRaceBehavesLikeABareTransport() {
        FakeTransport only = new FakeTransport();
        RecordingListener listener = new RecordingListener();
        TransportRace race = TransportRace.of(Collections.singletonList(
                TransportRace.Leg.of(TransportMode.BLE_PERIPHERAL_SERVER, only)));
        race.start(listener);

        only.listener.onConnected();
        only.listener.onMessage(new byte[]{3});
        race.send(new byte[]{4});
        only.listener.onDisconnected();

        assertEquals(1, listener.connected);
        assertEquals(1, listener.messages.size());
        assertEquals(1, only.sent.size());
        assertEquals(1, listener.disconnected);
        assertEquals(0, only.stopped);
    }

    @Test
    public void anEmptyRaceIsRejected() {
        boolean threw = false;
        try {
            TransportRace.of(Collections.emptyList());
        } catch (ProximityException e) {
            threw = true;
        }
        assertTrue(threw);
    }

    @Test
    public void sendBeforeAnyLegConnectsIsAnError() {
        FakeTransport central = new FakeTransport();
        FakeTransport peripheral = new FakeTransport();
        TransportRace race = raceOf(central, peripheral);
        race.start(new RecordingListener());

        boolean threw = false;
        try {
            race.send(new byte[]{1});
        } catch (ProximityException e) {
            threw = true;
        }
        assertTrue(threw);
        assertTrue(central.sent.isEmpty());
    }

    @Test
    public void aLegThatFailsTwiceDoesNotFailTheRace() {
        FakeTransport central = new FakeTransport();
        FakeTransport peripheral = new FakeTransport();
        RecordingListener listener = new RecordingListener();
        raceOf(central, peripheral).start(listener);

        central.listener.onError(new ProximityException("scan permission denied"));
        central.listener.onError(new ProximityException("scan permission denied again"));

        assertNull(listener.error);

        peripheral.listener.onConnected();

        assertEquals(1, listener.connected);
    }

    @Test
    public void aConnectionAfterEveryLegFailedIsNotSurfaced() {
        FakeTransport central = new FakeTransport();
        FakeTransport peripheral = new FakeTransport();
        RecordingListener listener = new RecordingListener();
        raceOf(central, peripheral).start(listener);

        central.listener.onError(new ProximityException("scan permission denied"));
        peripheral.listener.onError(new ProximityException("advertise unsupported"));

        central.listener.onConnected();

        assertEquals(0, listener.connected);
        assertEquals(1, listener.errorCount);
    }

    @Test
    public void aLoserThatDisconnectsDuringStopDoesNotSurface() {
        FakeTransport central = new FakeTransport();
        DisconnectingOnStopTransport peripheral = new DisconnectingOnStopTransport();
        RecordingListener listener = new RecordingListener();
        TransportRace race = TransportRace.of(Arrays.asList(
                TransportRace.Leg.of(TransportMode.BLE_CENTRAL_CLIENT, central),
                TransportRace.Leg.of(TransportMode.BLE_PERIPHERAL_SERVER, peripheral)));
        race.start(listener);

        central.listener.onConnected();

        assertEquals(0, listener.disconnected);
        assertEquals(1, peripheral.stopped);
    }

    @Test
    public void aLegThatConnectsSynchronouslyDuringStartStillNotifiesTheHost() {
        SynchronouslyConnectingTransport central = new SynchronouslyConnectingTransport();
        FakeTransport peripheral = new FakeTransport();
        RecordingListener listener = new RecordingListener();
        TransportRace race = TransportRace.of(Arrays.asList(
                TransportRace.Leg.of(TransportMode.BLE_CENTRAL_CLIENT, central),
                TransportRace.Leg.of(TransportMode.BLE_PERIPHERAL_SERVER, peripheral)));

        race.start(listener);

        assertEquals(1, listener.connected);
        assertEquals(2, peripheral.stopped);
    }

    @Test
    public void aCallbackArrivingAfterStopIsNotSurfacedToTheHost() {
        FakeTransport central = new FakeTransport();
        FakeTransport peripheral = new FakeTransport();
        RecordingListener listener = new RecordingListener();
        TransportRace race = raceOf(central, peripheral);
        race.start(listener);
        central.listener.onConnected();

        race.stop();
        central.listener.onMessage(new byte[]{5});
        peripheral.listener.onConnected();

        assertEquals(1, listener.connected);
        assertTrue(listener.messages.isEmpty());
    }

    @Test
    public void startingTwiceThrows() {
        FakeTransport central = new FakeTransport();
        FakeTransport peripheral = new FakeTransport();
        TransportRace race = raceOf(central, peripheral);
        race.start(new RecordingListener());

        boolean threw = false;
        try {
            race.start(new RecordingListener());
        } catch (ProximityException e) {
            threw = true;
        }
        assertTrue(threw);
    }
    @Test
    public void aLegCrashingWithARawRuntimeExceptionDoesNotEscapeTheRace() {
        FakeTransport central = new FakeTransport();
        central.startFailure = new SecurityException("missing BLUETOOTH_SCAN");
        FakeTransport peripheral = new FakeTransport();
        RecordingListener listener = new RecordingListener();

        raceOf(central, peripheral).start(listener);

        assertNull(listener.error);
        assertEquals(1, peripheral.started);
    }

    @Test
    public void everyLegCrashingWithARawRuntimeExceptionSurfacesOneFailure() {
        FakeTransport central = new FakeTransport();
        central.startFailure = new SecurityException("missing BLUETOOTH_SCAN");
        FakeTransport peripheral = new FakeTransport();
        peripheral.startFailure = new IllegalStateException("adapter off");
        RecordingListener listener = new RecordingListener();

        raceOf(central, peripheral).start(listener);

        assertNotNull(listener.error);
        assertEquals(1, listener.errorCount);
    }

    @Test
    public void stoppingBeforeStartingLeavesNoLegRunning() {
        FakeTransport central = new FakeTransport();
        FakeTransport peripheral = new FakeTransport();
        TransportRace race = raceOf(central, peripheral);

        race.stop();
        race.start(new RecordingListener());

        assertEquals(0, central.started);
        assertEquals(0, peripheral.started);
        assertTrue(central.stopped > 0);
        assertTrue(peripheral.stopped > 0);
    }

    @Test
    public void aLegConnectingAfterAStopIsNotSurfaced() {
        FakeTransport central = new FakeTransport();
        FakeTransport peripheral = new FakeTransport();
        TransportRace race = raceOf(central, peripheral);
        RecordingListener listener = new RecordingListener();
        race.start(listener);

        race.stop();
        central.listener.onConnected();

        assertEquals(0, listener.connected);
    }

    private static TransportRace raceOf(FakeTransport central, FakeTransport peripheral,
                                        FakeTransport wifi) {
        return TransportRace.of(Arrays.asList(
                TransportRace.Leg.of(TransportMode.BLE_CENTRAL_CLIENT, central),
                TransportRace.Leg.of(TransportMode.BLE_PERIPHERAL_SERVER, peripheral),
                TransportRace.Leg.of(TransportMode.WIFI_AWARE, wifi)));
    }

    @Test
    public void aWifiAwareLegThatConnectsFirstWinsAndStopsBothBleLegs() {
        FakeTransport central = new FakeTransport();
        FakeTransport peripheral = new FakeTransport();
        FakeTransport wifi = new FakeTransport();
        RecordingListener listener = new RecordingListener();
        TransportRace race = raceOf(central, peripheral, wifi);
        race.start(listener);

        wifi.listener.onConnected();
        wifi.listener.onMessage(new byte[]{7});
        race.send(new byte[]{8});

        assertEquals(1, listener.connected);
        assertEquals(1, listener.messages.size());
        assertEquals(1, wifi.sent.size());
        assertEquals(0, wifi.stopped);
        assertTrue(central.stopped > 0);
        assertTrue(peripheral.stopped > 0);
    }

    @Test
    public void aWifiAwareLegThatLosesIsStoppedAndItsLaterEventsAreIgnored() {
        FakeTransport central = new FakeTransport();
        FakeTransport peripheral = new FakeTransport();
        FakeTransport wifi = new FakeTransport();
        RecordingListener listener = new RecordingListener();
        TransportRace race = raceOf(central, peripheral, wifi);
        race.start(listener);

        peripheral.listener.onConnected();
        wifi.listener.onConnected();
        wifi.listener.onMessage(new byte[]{9});
        wifi.listener.onDisconnected();

        assertEquals(1, listener.connected);
        assertTrue(listener.messages.isEmpty());
        assertEquals(0, listener.disconnected);
        assertTrue(wifi.stopped > 0);
        assertEquals(0, peripheral.stopped);
    }
}
