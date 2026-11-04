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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.upokecenter.cbor.CBORObject;

import org.junit.Test;
import org.omnione.did.proximity.sdk.core.cbor.CborTags;
import org.omnione.did.proximity.sdk.core.cbor.CoseKeys;
import org.omnione.did.proximity.sdk.core.engagement.BleMode;
import org.omnione.did.proximity.sdk.core.engagement.BleTransferMode;
import org.omnione.did.proximity.sdk.core.engagement.DeviceEngagement;
import org.omnione.did.proximity.sdk.core.engagement.EngagementCodec;
import org.omnione.did.proximity.sdk.core.engagement.ModePreference;
import org.omnione.did.proximity.sdk.core.engagement.NfcOptions;
import org.omnione.did.proximity.sdk.core.engagement.SessionTranscripts;
import org.omnione.did.proximity.sdk.core.engagement.TransportMode;
import org.omnione.did.proximity.sdk.core.engagement.TransportPreference;
import org.omnione.did.proximity.sdk.core.engagement.UnsupportedTransportException;
import org.omnione.did.proximity.sdk.core.engagement.WifiOptions;
import org.omnione.did.proximity.sdk.core.ProximityException;
import org.omnione.did.proximity.sdk.core.session.SessionCrypto;
import org.omnione.did.proximity.sdk.core.session.SessionKeys;
import org.omnione.did.proximity.sdk.core.transport.Transport;
import org.omnione.did.proximity.sdk.core.transport.ble.BleIdent;
import org.omnione.did.proximity.sdk.core.transport.ble.L2capServer;
import org.omnione.did.proximity.sdk.core.transport.nfc.EnvelopeFraming;
import org.omnione.did.proximity.sdk.core.transport.nfc.NfcHolderTransport;
import org.omnione.did.proximity.sdk.core.util.Deadline;
import org.omnione.did.proximity.sdk.core.util.NoDeadline;

import java.security.KeyPair;
import java.security.interfaces.ECPublicKey;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class HolderSessionModeTest {

    private static final String MDL = "org.iso.18013.5.1.mDL";
    private static final String NS = "org.iso.18013.5.1";

    private static final class RecordingTransport implements Transport {
        final List<String> timeline = new ArrayList<>();
        Listener listener;
        ProximityException startFailure;

        @Override
        public void start(@NonNull Listener listener) {
            this.listener = listener;
            timeline.add("start");
            if (startFailure != null) {
                throw startFailure;
            }
        }

        @Override
        public void send(@NonNull byte[] message) {
            timeline.add("send");
        }

        @Override
        public void stop() {
            timeline.add("stop");
        }
    }

    private static final class FakeL2capServer implements L2capServer {
        final int psm;
        int closes;

        FakeL2capServer(int psm) {
            this.psm = psm;
        }

        @Override
        public int psm() {
            return psm;
        }

        @Override
        public void close() {
            closes++;
        }
    }

    private static final class RecordingBuilder implements HolderSession.TransportBuilder {
        final RecordingTransport transport = new RecordingTransport();
        UUID builtWith;
        byte[] centralClientIdent;
        int centralClientCalls;
        int peripheralServerCalls;
        L2capServer peripheralL2cap;
        int nfcCalls;
        NfcOptions nfcOptions;
        NfcHolderTransport nfcTransport;

        @NonNull
        @Override
        public NfcHolderTransport nfc(@NonNull NfcOptions options) {
            nfcCalls++;
            nfcOptions = options;
            nfcTransport = new NfcHolderTransport(options);
            return nfcTransport;
        }

        @NonNull
        @Override
        public Transport centralClient(@NonNull UUID serviceUuid,
                                       @NonNull byte[] expectedIdent) {
            builtWith = serviceUuid;
            centralClientIdent = expectedIdent;
            centralClientCalls++;
            return transport;
        }

        @NonNull
        @Override
        public Transport peripheralServer(@NonNull UUID serviceUuid) {
            return peripheralServer(serviceUuid, null);
        }

        @NonNull
        @Override
        public Transport peripheralServer(@NonNull UUID serviceUuid,
                                          @Nullable L2capServer l2cap) {
            builtWith = serviceUuid;
            peripheralServerCalls++;
            peripheralL2cap = l2cap;
            return transport;
        }
    }

    private static final class GattOnlyBuilder implements HolderSession.TransportBuilder {
        @NonNull
        @Override
        public Transport centralClient(@NonNull UUID serviceUuid,
                                       @NonNull byte[] expectedIdent) {
            return new RecordingTransport();
        }

        @NonNull
        @Override
        public Transport peripheralServer(@NonNull UUID serviceUuid) {
            return new RecordingTransport();
        }
    }

    private static final class DualBuilder implements HolderSession.TransportBuilder {
        final RecordingTransport central = new RecordingTransport();
        final RecordingTransport peripheral = new RecordingTransport();
        final RecordingTransport wifi = new RecordingTransport();
        UUID centralBuiltWith;
        UUID peripheralBuiltWith;
        L2capServer peripheralL2cap;
        byte[] centralClientIdent;
        byte[] wifiKeyBytes;
        WifiOptions wifiOptions;
        int wifiCalls;

        @NonNull
        @Override
        public Transport centralClient(@NonNull UUID serviceUuid,
                                       @NonNull byte[] expectedIdent) {
            centralBuiltWith = serviceUuid;
            centralClientIdent = expectedIdent;
            return central;
        }

        @NonNull
        @Override
        public Transport peripheralServer(@NonNull UUID serviceUuid) {
            return peripheralServer(serviceUuid, null);
        }

        @NonNull
        @Override
        public Transport peripheralServer(@NonNull UUID serviceUuid,
                                          @Nullable L2capServer l2cap) {
            peripheralBuiltWith = serviceUuid;
            peripheralL2cap = l2cap;
            return peripheral;
        }

        @NonNull
        @Override
        public Transport wifiAware(@NonNull byte[] eDeviceKeyBytes,
                                   @NonNull WifiOptions options) {
            wifiKeyBytes = eDeviceKeyBytes;
            wifiOptions = options;
            wifiCalls++;
            return wifi;
        }
    }

    private static final WifiOptions BANDS = WifiOptions.of(new byte[]{0x14});

    @Test
    public void transferConfigCarriesTheBleModeAndTheWifiAwareFlag() {
        TransferConfig config = TransferConfig.ble(BleTransferMode.CENTRAL_CLIENT);

        assertEquals(BleTransferMode.CENTRAL_CLIENT, config.bleMode());
        assertFalse(config.wifiAware());
        assertTrue(config.withWifiAware(true).wifiAware());
        assertEquals(BleTransferMode.CENTRAL_CLIENT, config.withWifiAware(true).bleMode());
        assertFalse(config.withWifiAware(true).withWifiAware(false).wifiAware());
    }

    @Test
    public void wifiAwareAddsATypeThreeMethodNextToTheBleOne() {
        DualBuilder builder = new DualBuilder();
        HolderSession session = HolderSession.create(new ManualDeadline(),
                TransferConfig.ble(BleTransferMode.BOTH).withWifiAware(true), BANDS, builder);

        CBORObject methods = CBORObject.DecodeFromBytes(
                EngagementCodec.decode(session.deviceEngagementQr())).get(CBORObject.FromObject(2));

        assertEquals(2, methods.size());
        assertEquals(2, methods.get(0).get(0).AsInt32Value());
        assertEquals(1, methods.get(0).get(1).AsInt32Value());
        assertTrue(methods.get(0).get(2).get(0).AsBoolean());
        assertTrue(methods.get(0).get(2).get(1).AsBoolean());
        assertEquals(3, methods.get(1).get(0).AsInt32Value());
        assertEquals(1, methods.get(1).get(1).AsInt32Value());
        assertEquals(1, methods.get(1).get(2).size());
        assertArrayEquals(new byte[]{0x14}, methods.get(1).get(2).get(3).GetByteString());
    }

    @Test
    public void wifiAwareBuildsTheWifiTransportOnceWithTheEngagementKeyBytes() {
        DualBuilder builder = new DualBuilder();
        HolderSession session = HolderSession.create(new ManualDeadline(),
                TransferConfig.ble(BleTransferMode.PERIPHERAL_SERVER).withWifiAware(true),
                BANDS, builder);
        DeviceEngagement parsed = DeviceEngagement.parse(
                EngagementCodec.decode(session.deviceEngagementQr()),
                TransportPreference.of(TransportMode.WIFI_AWARE));

        session.start(listener());

        assertEquals(1, builder.wifiCalls);
        assertArrayEquals(parsed.eDeviceKeyBytes(), builder.wifiKeyBytes);
        assertEquals(BANDS, builder.wifiOptions);
        assertEquals(List.of("start"), builder.wifi.timeline);
        assertEquals(List.of("start"), builder.peripheral.timeline);
        assertTrue(builder.central.timeline.isEmpty());
    }

    @Test
    public void withoutWifiAwareTheQrStaysBleOnly() {
        DualBuilder builder = new DualBuilder();
        HolderSession session = HolderSession.create(new ManualDeadline(),
                TransferConfig.ble(BleTransferMode.BOTH), BANDS, builder);

        CBORObject methods = CBORObject.DecodeFromBytes(
                EngagementCodec.decode(session.deviceEngagementQr())).get(CBORObject.FromObject(2));

        session.start(listener());

        assertEquals(1, methods.size());
        assertEquals(0, builder.wifiCalls);
    }

    @Test
    public void aBuilderWithoutWifiAwareRejectsAWifiAwareConfig() {
        RecordingBuilder builder = new RecordingBuilder();

        assertThrows(UnsupportedTransportException.class, () -> HolderSession.create(
                new ManualDeadline(), TransferConfig.ble(BleTransferMode.BOTH).withWifiAware(true),
                BANDS, builder));
    }

    @Test
    public void theWifiLegWinningStopsTheBleLegs() {
        DualBuilder builder = new DualBuilder();
        HolderSession session = HolderSession.create(new ManualDeadline(),
                TransferConfig.ble(BleTransferMode.BOTH).withWifiAware(true), BANDS, builder);
        session.start(listener());

        builder.wifi.listener.onConnected();

        assertEquals(List.of("start", "stop"), builder.central.timeline);
        assertEquals(List.of("start", "stop"), builder.peripheral.timeline);
        assertEquals(List.of("start"), builder.wifi.timeline);
    }

    private static Map<BleMode, UUID> advertisedUuids(@NonNull String qr) {
        byte[] encoded = EngagementCodec.decode(qr);
        Map<BleMode, UUID> uuids = new LinkedHashMap<>();
        uuids.put(BleMode.CENTRAL_CLIENT, DeviceEngagement
                .parse(encoded, ModePreference.CENTRAL_CLIENT_FIRST).serviceUuid());
        uuids.put(BleMode.PERIPHERAL_SERVER, DeviceEngagement
                .parse(encoded, ModePreference.PERIPHERAL_SERVER_FIRST).serviceUuid());
        return uuids;
    }

    private static final class ManualDeadline implements Deadline {
        final List<Long> delays = new ArrayList<>();
        final List<Runnable> expiries = new ArrayList<>();
        long scheduledDelayMs = -1;
        int scheduledCount;
        int cancelled;

        @NonNull
        @Override
        public Cancellable schedule(long delayMs, @NonNull Runnable onExpiry) {
            this.delays.add(delayMs);
            this.expiries.add(onExpiry);
            this.scheduledDelayMs = delayMs;
            this.scheduledCount++;
            return () -> cancelled++;
        }

        void fire() {
            fire(HolderSession.ENGAGEMENT_TIMEOUT_MS);
        }

        void fire(long delayMs) {
            int index = delays.lastIndexOf(delayMs);
            assertTrue("no deadline scheduled at " + delayMs + "ms", index >= 0);
            expiries.get(index).run();
        }
    }

    @Test
    public void centralClientEngagementAdvertisesCentralClientMode() {
        HolderSession session = HolderSession.create(
                new RecordingTransport(), new ManualDeadline(), BleMode.CENTRAL_CLIENT);

        DeviceEngagement parsed = DeviceEngagement.parse(
                EngagementCodec.decode(session.deviceEngagementQr()),
                ModePreference.CENTRAL_CLIENT_FIRST);

        assertEquals(BleMode.CENTRAL_CLIENT, parsed.mode());
    }

    @Test
    public void theDefaultEngagementStaysPeripheralServerMode() {
        HolderSession session = HolderSession.create(
                new RecordingTransport(), new NoDeadline(), BleMode.PERIPHERAL_SERVER);

        DeviceEngagement parsed = DeviceEngagement.parse(
                EngagementCodec.decode(session.deviceEngagementQr()));

        assertEquals(BleMode.PERIPHERAL_SERVER, parsed.mode());
    }

    @Test
    public void theEngagementCarriesTheUuidTheTransportIsBuiltWith() {
        RecordingBuilder builder = new RecordingBuilder();
        HolderSession session = HolderSession.create(
                new ManualDeadline(), BleTransferMode.CENTRAL_CLIENT, builder);
        DeviceEngagement parsed = DeviceEngagement.parse(
                EngagementCodec.decode(session.deviceEngagementQr()),
                ModePreference.CENTRAL_CLIENT_FIRST);

        assertEquals(parsed.serviceUuid(), builder.builtWith);
    }

    @Test
    public void centralClientModeBuildsTheCentralClientTransportWithTheEngagementIdent() {
        RecordingBuilder builder = new RecordingBuilder();
        HolderSession session = HolderSession.create(
                new ManualDeadline(), BleTransferMode.CENTRAL_CLIENT, builder);

        DeviceEngagement parsed = DeviceEngagement.parse(
                EngagementCodec.decode(session.deviceEngagementQr()),
                ModePreference.CENTRAL_CLIENT_FIRST);

        assertEquals(1, builder.centralClientCalls);
        assertEquals(0, builder.peripheralServerCalls);
        assertArrayEquals(BleIdent.derive(parsed.eDeviceKeyBytes()), builder.centralClientIdent);
    }

    @Test
    public void peripheralServerModeBuildsThePeripheralServerTransport() {
        RecordingBuilder builder = new RecordingBuilder();

        HolderSession.create(new ManualDeadline(), BleTransferMode.PERIPHERAL_SERVER, builder);

        assertEquals(1, builder.peripheralServerCalls);
        assertEquals(0, builder.centralClientCalls);
        assertNull(builder.centralClientIdent);
    }

    @Test
    public void startArmsTheEstablishmentDeadline() {
        ManualDeadline deadline = new ManualDeadline();
        HolderSession session = HolderSession.create(
                new RecordingTransport(), deadline, BleMode.CENTRAL_CLIENT);

        session.start(listener());

        assertEquals(1, deadline.scheduledCount);
        assertEquals(90_000L, HolderSession.ENGAGEMENT_TIMEOUT_MS);
        assertEquals(HolderSession.ENGAGEMENT_TIMEOUT_MS, deadline.scheduledDelayMs);
    }

    @Test
    public void startingTwiceIsRejectedAndDoesNotArmASecondDeadline() {
        ManualDeadline deadline = new ManualDeadline();
        HolderSession session = HolderSession.create(
                new RecordingTransport(), deadline, BleMode.CENTRAL_CLIENT);
        session.start(listener());

        assertThrows(ProximityException.class, () -> session.start(listener()));

        assertEquals(1, deadline.scheduledCount);
    }

    @Test
    public void theDeadlineFiringReportsAnError() {
        ManualDeadline deadline = new ManualDeadline();
        HolderSession session = HolderSession.create(
                new RecordingTransport(), deadline, BleMode.CENTRAL_CLIENT);
        CapturingListener listener = listener();
        session.start(listener);

        deadline.fire();

        assertNotNull(listener.error);
        assertTrue(listener.error.getMessage().contains("session establishment"));
    }

    @Test
    public void expiryFailsTheSessionAndStopsTheTransport() {
        ManualDeadline deadline = new ManualDeadline();
        RecordingTransport transport = new RecordingTransport();
        HolderSession session = HolderSession.create(
                transport, deadline, BleMode.CENTRAL_CLIENT);
        CapturingListener listener = listener();
        session.start(listener);

        deadline.fire();

        assertNotNull(listener.error);
        assertTrue(transport.timeline.contains("stop"));
        assertFalse(listener.connected);
    }

    @Test
    public void expiryAfterEstablishmentIsIgnored() {
        ManualDeadline deadline = new ManualDeadline();
        RecordingTransport transport = new RecordingTransport();
        HolderSession session = HolderSession.create(
                transport, deadline, BleMode.CENTRAL_CLIENT);
        String qr = session.deviceEngagementQr();
        CapturingListener listener = listener();
        session.start(listener);
        establish(transport, qr);

        deadline.fire();

        assertNull(listener.error);
    }

    @Test
    public void expiryThatRacesCancellationIsIgnored() {
        ManualDeadline deadline = new ManualDeadline();
        RecordingTransport transport = new RecordingTransport();
        HolderSession session = HolderSession.create(
                transport, deadline, BleMode.CENTRAL_CLIENT);
        String qr = session.deviceEngagementQr();
        CapturingListener listener = listener();
        session.start(listener);
        establish(transport, qr);
        assertEquals(1, deadline.cancelled);

        deadline.fire();

        assertNull(listener.error);
        assertFalse(transport.timeline.contains("stop"));
    }

    @Test
    public void expiryAfterAnEarlierErrorDoesNotDeliverASecondError() {
        ManualDeadline deadline = new ManualDeadline();
        RecordingTransport transport = new RecordingTransport();
        HolderSession session = HolderSession.create(
                transport, deadline, BleMode.CENTRAL_CLIENT);
        CapturingListener listener = listener();
        session.start(listener);
        ProximityException original = new ProximityException("scan permission denied");

        transport.listener.onError(original);
        deadline.fire();

        assertEquals(1, listener.errorCount);
        assertEquals(original, listener.error);
        assertTrue(transport.timeline.contains("stop"));
    }

    @Test
    public void anIdentMismatchEndsTheSessionAndStopsTheTransport() {
        ManualDeadline deadline = new ManualDeadline();
        RecordingTransport transport = new RecordingTransport();
        HolderSession session = HolderSession.create(
                transport, deadline, BleMode.CENTRAL_CLIENT);
        CapturingListener listener = listener();
        session.start(listener);

        transport.listener.onError(new ProximityException(
                "the mdoc reader ident does not match the expected value"));

        assertEquals(1, listener.errorCount);
        assertTrue(transport.timeline.contains("stop"));
        assertEquals(1, deadline.cancelled);
    }

    @Test
    public void aTransportThatFailsToStartDisarmsTheDeadline() {
        ManualDeadline deadline = new ManualDeadline();
        RecordingTransport transport = new RecordingTransport();
        transport.startFailure = new ProximityException("Bluetooth is off");
        HolderSession session = HolderSession.create(
                transport, deadline, BleMode.CENTRAL_CLIENT);
        CapturingListener listener = listener();

        session.start(listener);

        assertEquals(1, listener.errorCount);
        assertEquals(1, deadline.cancelled);
        deadline.fire();
        assertEquals(1, listener.errorCount);
    }

    @Test
    public void establishingTheSessionDisarmsTheDeadline() {
        RecordingTransport transport = new RecordingTransport();
        ManualDeadline deadline = new ManualDeadline();
        HolderSession session = HolderSession.create(
                transport, deadline, BleMode.CENTRAL_CLIENT);
        String qr = session.deviceEngagementQr();
        CapturingListener listener = listener();
        session.start(listener);

        establish(transport, qr);

        assertEquals(1, deadline.cancelled);
        assertNotNull(listener.request);
        assertEquals(MDL, listener.request.docType);
        assertEquals(Boolean.FALSE,
                listener.request.requestedElements.get(NS).get("family_name"));
    }

    @Test
    public void closingEndsTheSessionAndDisarmsTheDeadline() {
        ManualDeadline deadline = new ManualDeadline();
        HolderSession session = HolderSession.create(
                new RecordingTransport(), deadline, BleMode.CENTRAL_CLIENT);
        session.start(listener());

        session.closeWithoutNotifying();

        assertEquals(1, deadline.cancelled);
    }

    @Test
    public void bothModeOpensOneLegPerModeSharingOneUuid() {
        DualBuilder builder = new DualBuilder();
        HolderSession session = HolderSession.create(
                new ManualDeadline(), BleTransferMode.BOTH, builder);
        String qr = session.deviceEngagementQr();

        session.start(listener());

        Map<BleMode, UUID> advertised = advertisedUuids(qr);
        assertEquals("ISO 18013-5 11.1.2 allows one UUID per NFC Handover Select message, so a"
                        + " session that can be engaged over NFC must use a single UUID; the QR"
                        + " encoding then repeats it under both BleOptions keys",
                advertised.get(BleMode.CENTRAL_CLIENT),
                advertised.get(BleMode.PERIPHERAL_SERVER));
        assertEquals(advertised.get(BleMode.CENTRAL_CLIENT), builder.centralBuiltWith);
        assertEquals(advertised.get(BleMode.PERIPHERAL_SERVER), builder.peripheralBuiltWith);
        assertEquals("both legs must race on the same UUID",
                builder.centralBuiltWith, builder.peripheralBuiltWith);
    }

    @Test
    public void bothModeDerivesTheIdentFromTheEngagementKey() {
        DualBuilder builder = new DualBuilder();
        HolderSession session = HolderSession.create(
                new ManualDeadline(), BleTransferMode.BOTH, builder);
        String qr = session.deviceEngagementQr();

        session.start(listener());

        byte[] expected = BleIdent.derive(CBORObject.DecodeFromBytes(
                EngagementCodec.decode(qr)).get(CBORObject.FromObject(1)).get(1).EncodeToBytes());
        assertArrayEquals(expected, builder.centralClientIdent);
    }

    @Test
    public void theLosingLegIsStoppedWhenTheOtherConnects() {
        DualBuilder builder = new DualBuilder();
        HolderSession session = HolderSession.create(
                new ManualDeadline(), BleTransferMode.BOTH, builder);
        CapturingListener listener = listener();
        session.start(listener);

        builder.central.listener.onConnected();

        assertTrue(listener.connected);
        assertTrue(builder.peripheral.timeline.contains("stop"));
        assertFalse(builder.central.timeline.contains("stop"));
    }

    @Test
    public void oneLegFailingDoesNotEndABothModeSession() {
        DualBuilder builder = new DualBuilder();
        HolderSession session = HolderSession.create(
                new ManualDeadline(), BleTransferMode.BOTH, builder);
        CapturingListener listener = listener();
        session.start(listener);

        builder.central.listener.onError(new ProximityException("scan permission denied"));

        assertNull(listener.error);

        builder.peripheral.listener.onConnected();

        assertTrue(listener.connected);
    }

    @Test
    public void bothLegsFailingEndsTheSession() {
        DualBuilder builder = new DualBuilder();
        HolderSession session = HolderSession.create(
                new ManualDeadline(), BleTransferMode.BOTH, builder);
        CapturingListener listener = listener();
        session.start(listener);

        builder.central.listener.onError(new ProximityException("scan permission denied"));
        builder.peripheral.listener.onError(new ProximityException("advertise unsupported"));

        assertNotNull(listener.error);
    }

    @Test
    public void bothModeSchedulesASingleEstablishmentDeadline() {
        ManualDeadline deadline = new ManualDeadline();
        HolderSession session = HolderSession.create(
                deadline, BleTransferMode.BOTH, new DualBuilder());

        session.start(listener());

        assertEquals(1, deadline.scheduledCount);
        assertEquals(90_000L, deadline.scheduledDelayMs);
    }

    @Test
    public void aSingleModeSessionStillOpensExactlyOneLeg() {
        DualBuilder builder = new DualBuilder();
        HolderSession session = HolderSession.create(
                new ManualDeadline(), BleTransferMode.CENTRAL_CLIENT, builder);

        session.start(listener());

        assertEquals(List.of("start"), builder.central.timeline);
        assertTrue(builder.peripheral.timeline.isEmpty());
    }

    private static void establish(@NonNull RecordingTransport transport, @NonNull String qr) {
        byte[] engagement = EngagementCodec.decode(qr);
        KeyPair readerPair = CoseKeys.generateEphemeral();
        byte[] readerKeyBytes = CoseKeys
                .toCoseKey((ECPublicKey) readerPair.getPublic()).EncodeToBytes();
        byte[] transcript = SessionTranscripts.forQr(engagement, readerKeyBytes);
        ECPublicKey eDeviceKey = CoseKeys.toPublicKey(CBORObject.DecodeFromBytes(
                CborTags.untag(CBORObject.DecodeFromBytes(engagement)
                        .get(CBORObject.FromObject(1)).get(1))));
        SessionCrypto readerCrypto = SessionCrypto.forReader(
                SessionKeys.derive(readerPair.getPrivate(), eDeviceKey, transcript));

        CBORObject establishment = CBORObject.NewMap();
        establishment.Add("eReaderKey", CborTags.tagged(readerKeyBytes));
        establishment.Add("data", readerCrypto.encrypt(deviceRequestCbor()));

        transport.listener.onConnected();
        transport.listener.onMessage(establishment.EncodeToBytes());
    }

    private static byte[] deviceRequestCbor() {
        CBORObject elements = CBORObject.NewMap();
        elements.Add("family_name", false);

        CBORObject nameSpaces = CBORObject.NewMap();
        nameSpaces.Add(NS, elements);

        CBORObject itemsRequest = CBORObject.NewMap();
        itemsRequest.Add("docType", MDL);
        itemsRequest.Add("nameSpaces", nameSpaces);

        CBORObject docRequest = CBORObject.NewMap();
        docRequest.Add("itemsRequest", CborTags.tagged(itemsRequest.EncodeToBytes()));

        CBORObject docRequests = CBORObject.NewArray();
        docRequests.Add(docRequest);

        CBORObject request = CBORObject.NewMap();
        request.Add("version", "1.0");
        request.Add("docRequests", docRequests);
        return request.EncodeToBytes();
    }

    private static final class CapturingListener implements HolderSession.Listener {
        boolean connected;
        ProximityRequest request;
        ProximityException error;
        int errorCount;

        @Override public void onReaderConnected() { connected = true; }
        @Override public void onRequest(@NonNull ProximityRequest r) { request = r; }
        @Override public void onResponseSent() { }
        @Override public void onError(@NonNull ProximityException e) { error = e; errorCount++; }
        @Override public void onTerminated() { }
    }

    private static CapturingListener listener() {
        return new CapturingListener();
    }

    private static CBORObject bleOptionsOf(HolderSession session) {
        return CBORObject.DecodeFromBytes(EngagementCodec.decode(session.deviceEngagementQr()))
                .get(CBORObject.FromObject(2)).get(0).get(2);
    }

    private static final NfcOptions NFC = NfcOptions.of(0xFFFF, 0x10000);

    @Test
    public void nfcOptionsAddAPassiveNfcLegWithoutTouchingTheQrEngagement() {
        RecordingBuilder builder = new RecordingBuilder();

        HolderSession session = HolderSession.create(new ManualDeadline(),
                TransferConfig.ble(BleTransferMode.PERIPHERAL_SERVER), null, null, NFC, builder);

        assertEquals(1, builder.nfcCalls);
        assertEquals(NFC, builder.nfcOptions);
        assertTrue(session.nfcTransport() == builder.nfcTransport);
        assertEquals(NFC, session.nfcOptions());
        CBORObject methods = CBORObject.DecodeFromBytes(
                EngagementCodec.decode(session.deviceEngagementQr())).get(CBORObject.FromObject(2));
        assertEquals(1, methods.size());
        assertEquals(2, methods.get(0).get(0).AsInt32Value());
        assertEquals(1, builder.peripheralServerCalls);
    }

    @Test
    public void withoutNfcOptionsTheBuilderIsNeverAskedForAnNfcLeg() {
        RecordingBuilder builder = new RecordingBuilder();

        HolderSession session = HolderSession.create(new ManualDeadline(),
                TransferConfig.ble(BleTransferMode.PERIPHERAL_SERVER), null, null, builder);

        assertEquals(0, builder.nfcCalls);
        assertNull(session.nfcTransport());
        assertNull(session.nfcOptions());
    }

    @Test
    public void theNfcLegWinningStopsTheBleLeg() {
        RecordingBuilder builder = new RecordingBuilder();
        HolderSession session = HolderSession.create(new ManualDeadline(),
                TransferConfig.ble(BleTransferMode.PERIPHERAL_SERVER), null, null, NFC, builder);
        CapturingListener listener = listener();
        session.start(listener);
        assertEquals(List.of("start"), builder.transport.timeline);

        byte[] response = builder.nfcTransport.processCommandApdu(
                EnvelopeFraming.selectMdocApplication(), apdu -> { });

        assertArrayEquals(new byte[]{(byte) 0x90, 0x00}, response);
        assertTrue(listener.connected);
        assertEquals(List.of("start", "stop"), builder.transport.timeline);
        assertTrue(builder.nfcTransport.isLinkActive());
    }

    @Test
    public void anNfcLegAloneIsStillOneMoreLegThanBleOnly() {
        RecordingBuilder builder = new RecordingBuilder();
        HolderSession session = HolderSession.create(new ManualDeadline(),
                TransferConfig.ble(BleTransferMode.BOTH), null, null, NFC, builder);

        assertEquals(1, builder.centralClientCalls);
        assertEquals(1, builder.peripheralServerCalls);
        assertEquals(1, builder.nfcCalls);
        assertNotNull(session.nfcTransport());
    }

    @Test
    public void aBuilderWithoutNfcRejectsNfcOptions() {
        assertThrows(UnsupportedTransportException.class, () -> HolderSession.create(
                new ManualDeadline(), TransferConfig.ble(BleTransferMode.PERIPHERAL_SERVER),
                null, null, NFC, new GattOnlyBuilder()));
    }

    @Test
    public void anL2capServerPutsItsPsmIntoKey21AndReachesThePeripheralLeg() {
        RecordingBuilder builder = new RecordingBuilder();
        FakeL2capServer server = new FakeL2capServer(192);

        HolderSession session = HolderSession.create(new ManualDeadline(),
                TransferConfig.ble(BleTransferMode.PERIPHERAL_SERVER), null, server, builder);

        assertEquals(192, bleOptionsOf(session).get(21).AsInt32Value());
        assertEquals(1, builder.peripheralServerCalls);
        assertTrue(builder.peripheralL2cap == server);
        assertEquals(Integer.valueOf(192), session.l2capPsm());
    }

    @Test
    public void withoutAnL2capServerKey21IsAbsent() {
        RecordingBuilder builder = new RecordingBuilder();

        HolderSession session = HolderSession.create(new ManualDeadline(),
                TransferConfig.ble(BleTransferMode.PERIPHERAL_SERVER), null, null, builder);

        assertNull(bleOptionsOf(session).get(21));
        assertNull(builder.peripheralL2cap);
        assertNull(session.l2capPsm());
    }

    @Test
    public void aCentralClientOnlyConfigNeverBuildsThePeripheralServerNorAdvertisesAPsm() {
        RecordingBuilder builder = new RecordingBuilder();
        FakeL2capServer server = new FakeL2capServer(192);

        HolderSession session = HolderSession.create(new ManualDeadline(),
                TransferConfig.ble(BleTransferMode.CENTRAL_CLIENT), null, server, builder);

        assertEquals(0, builder.peripheralServerCalls);
        assertNull(bleOptionsOf(session).get(21));
        assertNull(session.l2capPsm());
    }

    @Test
    public void inBothModesOnlyThePeripheralLegReceivesTheL2capServer() {
        DualBuilder builder = new DualBuilder();
        FakeL2capServer server = new FakeL2capServer(192);

        HolderSession session = HolderSession.create(new ManualDeadline(),
                TransferConfig.ble(BleTransferMode.BOTH), null, server, builder);

        assertTrue(builder.peripheralL2cap == server);
        assertEquals(192, bleOptionsOf(session).get(21).AsInt32Value());
        assertTrue(bleOptionsOf(session).get(1).AsBoolean());
    }

    @Test
    public void aBuilderWithoutL2capRejectsAnL2capServer() {
        assertThrows(UnsupportedTransportException.class, () -> HolderSession.create(
                new ManualDeadline(), TransferConfig.ble(BleTransferMode.PERIPHERAL_SERVER),
                null, new FakeL2capServer(192), new GattOnlyBuilder()));
    }

    @Test
    public void aBuilderWithoutL2capStillBuildsWithoutAServer() {
        HolderSession session = HolderSession.create(
                new ManualDeadline(), TransferConfig.ble(BleTransferMode.PERIPHERAL_SERVER),
                null, null, new GattOnlyBuilder());

        assertNull(bleOptionsOf(session).get(21));
    }
}
