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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import androidx.annotation.NonNull;

import com.upokecenter.cbor.CBORObject;

import org.junit.Test;
import org.omnione.did.proximity.sdk.core.ProximityException;
import org.omnione.did.proximity.sdk.core.cbor.CborTags;
import org.omnione.did.proximity.sdk.core.cbor.CoseKeys;
import org.omnione.did.proximity.sdk.core.engagement.BleMode;
import org.omnione.did.proximity.sdk.core.engagement.BleTransferMode;
import org.omnione.did.proximity.sdk.core.engagement.EngagementCodec;
import org.omnione.did.proximity.sdk.core.engagement.SessionTranscripts;
import org.omnione.did.proximity.sdk.core.session.SessionCrypto;
import org.omnione.did.proximity.sdk.core.session.SessionKeys;
import org.omnione.did.proximity.sdk.core.session.SessionMessages;
import org.omnione.did.proximity.sdk.core.transport.Transport;
import org.omnione.did.proximity.sdk.core.util.Deadline;
import org.omnione.did.proximity.sdk.core.util.NoDeadline;

import java.nio.ByteBuffer;
import java.security.KeyPair;
import java.security.interfaces.ECPublicKey;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class HolderSessionTest {

    private static final String MDL = "org.iso.18013.5.1.mDL";
    private static final String NS = "org.iso.18013.5.1";

    private static final class FakeTransport implements Transport {
        final List<byte[]> sent = new ArrayList<>();
        Listener listener;
        boolean started;
        boolean stopped;

        @Override
        public void start(@NonNull Listener listener) {
            this.started = true;
            this.listener = listener;
        }

        @Override
        public void send(@NonNull byte[] message) {
            sent.add(message);
        }

        @Override
        public void stop() {
            stopped = true;
        }
    }

    private static final class RecordingListener implements HolderSession.Listener {
        boolean connected;
        ProximityRequest request;
        boolean responseSent;
        boolean terminated;
        ProximityException error;

        @Override public void onReaderConnected() { connected = true; }
        @Override public void onRequest(@NonNull ProximityRequest r) { request = r; }
        @Override public void onResponseSent() { responseSent = true; }
        @Override public void onError(@NonNull ProximityException e) { error = e; }
        @Override public void onTerminated() { terminated = true; }
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

    private static final class ManualDeadline implements Deadline {
        final List<Long> delays = new ArrayList<>();
        final List<Runnable> expiries = new ArrayList<>();
        int cancelled;

        @NonNull
        @Override
        public Cancellable schedule(long delayMs, @NonNull Runnable onExpiry) {
            delays.add(delayMs);
            expiries.add(onExpiry);
            return () -> cancelled++;
        }

        void fire(long delayMs) {
            int index = delays.lastIndexOf(delayMs);
            assertTrue("no deadline scheduled at " + delayMs + "ms", index >= 0);
            expiries.get(index).run();
        }
    }

    private static RecordingListener establishedSession(FakeTransport transport,
                                                        HolderSession session) {
        String qr = session.deviceEngagementQr();
        RecordingListener listener = new RecordingListener();
        session.start(listener);

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
        return listener;
    }

    @Test
    public void theResponseDeadlineIsArmedWhenTheRequestIsSurfaced() {
        FakeTransport transport = new FakeTransport();
        ManualDeadline deadline = new ManualDeadline();
        HolderSession session = HolderSession.create(
                transport, deadline, BleMode.PERIPHERAL_SERVER);

        RecordingListener listener = establishedSession(transport, session);

        assertNotNull(listener.request);
        assertEquals(60_000L, HolderSession.RESPONSE_TIMEOUT_MS);
        assertEquals(List.of(HolderSession.ENGAGEMENT_TIMEOUT_MS,
                HolderSession.RESPONSE_TIMEOUT_MS), deadline.delays);
    }

    @Test
    public void sendingTheResponseCancelsTheResponseDeadline() {
        FakeTransport transport = new FakeTransport();
        ManualDeadline deadline = new ManualDeadline();
        HolderSession session = HolderSession.create(
                transport, deadline, BleMode.PERIPHERAL_SERVER);
        RecordingListener listener = establishedSession(transport, session);
        int cancelledBefore = deadline.cancelled;

        session.sendResponse(new byte[]{(byte) 0xA0});

        assertTrue(listener.responseSent);
        assertEquals(cancelledBefore + 1, deadline.cancelled);

        deadline.fire(HolderSession.RESPONSE_TIMEOUT_MS);

        assertNull(listener.error);
    }

    @Test
    public void theResponseDeadlineSendsATerminationAndFailsWithItsReason() {
        FakeTransport transport = new FakeTransport();
        ManualDeadline deadline = new ManualDeadline();
        HolderSession session = HolderSession.create(
                transport, deadline, BleMode.PERIPHERAL_SERVER);
        RecordingListener listener = establishedSession(transport, session);
        int sentBefore = transport.sent.size();

        deadline.fire(HolderSession.RESPONSE_TIMEOUT_MS);

        assertEquals(sentBefore + 1, transport.sent.size());
        assertEquals(Integer.valueOf(SessionMessages.STATUS_SESSION_TERMINATION),
                SessionMessages.parseStatus(transport.sent.get(sentBefore)));
        assertNotNull(listener.error);
        assertEquals(ProximityException.Reason.RESPONSE_TIMEOUT, listener.error.reason());
        assertFalse(listener.terminated);
        assertTrue(transport.stopped);
    }

    @Test
    public void theEngagementDeadlineFailsWithItsOwnReason() {
        FakeTransport transport = new FakeTransport();
        ManualDeadline deadline = new ManualDeadline();
        HolderSession session = HolderSession.create(
                transport, deadline, BleMode.PERIPHERAL_SERVER);
        RecordingListener listener = new RecordingListener();
        session.start(listener);

        deadline.fire(HolderSession.ENGAGEMENT_TIMEOUT_MS);

        assertNotNull(listener.error);
        assertEquals(ProximityException.Reason.ENGAGEMENT_TIMEOUT, listener.error.reason());
    }

    @Test
    public void engagementQrIsEmittedAndTheTransportIsStarted() {
        FakeTransport transport = new FakeTransport();
        HolderSession session = HolderSession.create(
                transport, new NoDeadline(), BleMode.PERIPHERAL_SERVER);

        String qr = session.deviceEngagementQr();
        session.start(new RecordingListener());

        assertTrue(qr.startsWith("mdoc:"));
        assertTrue(transport.started);
        assertTrue(EngagementCodec.decode(qr).length > 0);
    }

    @Test
    public void readerRequestIsDecryptedParsedAndSurfaced() {
        FakeTransport transport = new FakeTransport();
        HolderSession session = HolderSession.create(
                transport, new NoDeadline(), BleMode.PERIPHERAL_SERVER);
        String qr = session.deviceEngagementQr();
        RecordingListener listener = new RecordingListener();
        session.start(listener);

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

        assertTrue(listener.connected);
        assertEquals(MDL, listener.request.docType);
        assertEquals(Boolean.FALSE, listener.request.requestedElements.get(NS).get("family_name"));
        assertArrayEquals(deviceRequestCbor(), listener.request.deviceRequest);
        CBORObject delivered = CBORObject.DecodeFromBytes(listener.request.sessionTranscript);
        assertEquals(CborTags.ENCODED_CBOR, delivered.getMostOuterTag().ToInt32Checked());
        assertArrayEquals(transcript, CborTags.untag(delivered));
        assertArrayEquals(SessionTranscripts.taggedBytes(transcript),
                listener.request.sessionTranscript);
    }

    @Test
    public void responseIsEncryptedAndReaderCanDecryptIt() {
        FakeTransport transport = new FakeTransport();
        HolderSession session = HolderSession.create(
                transport, new NoDeadline(), BleMode.PERIPHERAL_SERVER);
        String qr = session.deviceEngagementQr();
        RecordingListener listener = new RecordingListener();
        session.start(listener);

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

        byte[] response = new byte[]{(byte) 0xDE, (byte) 0xAD};
        session.sendResponse(response);

        assertTrue(listener.responseSent);
        assertEquals(1, transport.sent.size());
        byte[] encrypted = SessionMessages.parseData(transport.sent.get(0));
        assertArrayEquals(response, readerCrypto.decrypt(encrypted));
    }

    @Test
    public void terminateSendsTerminationAndStopsTransport() {
        FakeTransport transport = new FakeTransport();
        HolderSession session = HolderSession.create(
                transport, new NoDeadline(), BleMode.PERIPHERAL_SERVER);
        session.start(new RecordingListener());
        transport.listener.onConnected();

        session.terminate();

        assertEquals(1, transport.sent.size());
        assertEquals(20, CBORObject.DecodeFromBytes(transport.sent.get(0))
                .get("status").AsInt32Value());
        assertTrue(transport.stopped);
    }

    @Test
    public void terminateNotifiesTheListenerThatTheSessionEnded() {
        FakeTransport transport = new FakeTransport();
        HolderSession session = HolderSession.create(
                transport, new NoDeadline(), BleMode.PERIPHERAL_SERVER);
        RecordingListener listener = new RecordingListener();
        session.start(listener);
        transport.listener.onConnected();

        session.terminate();

        assertTrue(listener.terminated);
    }

    @Test
    public void closeWithoutNotifyingTellsTheReaderNothing() {
        FakeTransport transport = new FakeTransport();
        HolderSession session = HolderSession.create(
                transport, new NoDeadline(), BleMode.PERIPHERAL_SERVER);
        RecordingListener listener = new RecordingListener();
        session.start(listener);
        transport.listener.onConnected();

        session.closeWithoutNotifying();

        assertTrue("the quiet path must not put a frame on the wire",
                transport.sent.isEmpty());
        assertFalse(listener.terminated);
        assertNull(listener.error);
        assertTrue(transport.stopped);
    }

    @Test
    public void sendingResponseBeforeRequestIsAnError() {
        FakeTransport transport = new FakeTransport();
        HolderSession session = HolderSession.create(
                transport, new NoDeadline(), BleMode.PERIPHERAL_SERVER);
        RecordingListener listener = new RecordingListener();
        session.start(listener);

        session.sendResponse(new byte[]{1});

        assertNotNull(listener.error);
    }

    private static byte[] establishmentWithReaderKey(@NonNull byte[] readerKeyPayload) {
        CBORObject establishment = CBORObject.NewMap();
        establishment.Add("eReaderKey", CborTags.tagged(readerKeyPayload));
        establishment.Add("data", new byte[]{1, 2, 3});
        return establishment.EncodeToBytes();
    }

    private static UUID serviceUuidOf(@NonNull byte[] engagement) {
        byte[] raw = CBORObject.DecodeFromBytes(engagement)
                .get(CBORObject.FromObject(2))
                .get(0)
                .get(2)
                .get(CBORObject.FromObject(10))
                .GetByteString();
        ByteBuffer buffer = ByteBuffer.wrap(raw);
        return new UUID(buffer.getLong(), buffer.getLong());
    }

    @Test
    public void engagementQrAdvertisesTheUuidThePeripheralServerTransportIsBuiltWith() {
        FakeTransport transport = new FakeTransport();
        UUID[] builtWith = new UUID[1];
        HolderSession session = HolderSession.create(new NoDeadline(),
                BleTransferMode.PERIPHERAL_SERVER, new HolderSession.TransportBuilder() {
                    @NonNull
                    @Override
                    public Transport centralClient(@NonNull UUID serviceUuid,
                                                   @NonNull byte[] expectedIdent) {
                        throw new AssertionError("peripheral server mode only");
                    }

                    @NonNull
                    @Override
                    public Transport peripheralServer(@NonNull UUID serviceUuid) {
                        builtWith[0] = serviceUuid;
                        return transport;
                    }
                });

        String qr = session.deviceEngagementQr();
        session.start(new RecordingListener());

        assertEquals(builtWith[0], serviceUuidOf(EngagementCodec.decode(qr)));
    }

    @Test
    public void garbageFrameBeforeEstablishmentIsReportedAsAnError() {
        FakeTransport transport = new FakeTransport();
        HolderSession session = HolderSession.create(
                transport, new NoDeadline(), BleMode.PERIPHERAL_SERVER);
        RecordingListener listener = new RecordingListener();
        session.start(listener);

        transport.listener.onMessage(new byte[]{0x01});

        assertNotNull(listener.error);
    }

    @Test
    public void establishmentWithUndecodableReaderKeyIsReportedAsAnError() {
        FakeTransport transport = new FakeTransport();
        HolderSession session = HolderSession.create(
                transport, new NoDeadline(), BleMode.PERIPHERAL_SERVER);
        RecordingListener listener = new RecordingListener();
        session.start(listener);

        transport.listener.onMessage(establishmentWithReaderKey(new byte[0]));

        assertNotNull(listener.error);
    }

    @Test
    public void establishmentWithIntegerReaderKeyIsReportedAsAnError() {
        FakeTransport transport = new FakeTransport();
        HolderSession session = HolderSession.create(
                transport, new NoDeadline(), BleMode.PERIPHERAL_SERVER);
        RecordingListener listener = new RecordingListener();
        session.start(listener);

        transport.listener.onMessage(establishmentWithReaderKey(new byte[]{0x01}));

        assertNotNull(listener.error);
    }

    @Test
    public void establishmentWithArrayReaderKeyIsReportedAsAnError() {
        FakeTransport transport = new FakeTransport();
        HolderSession session = HolderSession.create(
                transport, new NoDeadline(), BleMode.PERIPHERAL_SERVER);
        RecordingListener listener = new RecordingListener();
        session.start(listener);

        transport.listener.onMessage(establishmentWithReaderKey(new byte[]{(byte) 0x80}));

        assertNotNull(listener.error);
    }

    @NonNull
    private static SessionCrypto establishSession(@NonNull FakeTransport transport,
                                                  @NonNull String qr) {
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
        return readerCrypto;
    }

    @NonNull
    private static byte[] statusFrame(int status) {
        CBORObject frame = CBORObject.NewMap();
        frame.Add("status", status);
        return frame.EncodeToBytes();
    }

    @Test
    public void readerTerminationStatusIsReportedAsTermination() {
        FakeTransport transport = new FakeTransport();
        HolderSession session = HolderSession.create(
                transport, new NoDeadline(), BleMode.PERIPHERAL_SERVER);
        String qr = session.deviceEngagementQr();
        RecordingListener listener = new RecordingListener();
        session.start(listener);
        establishSession(transport, qr);

        transport.listener.onMessage(statusFrame(20));

        assertTrue(listener.terminated);
        assertNull(listener.error);
    }

    @Test
    public void readerTerminationStopsTheTransport() {
        FakeTransport transport = new FakeTransport();
        HolderSession session = HolderSession.create(
                transport, new NoDeadline(), BleMode.PERIPHERAL_SERVER);
        String qr = session.deviceEngagementQr();
        session.start(new RecordingListener());
        establishSession(transport, qr);

        transport.listener.onMessage(statusFrame(20));

        assertTrue(transport.stopped);
    }

    @Test
    public void readerDisconnectStopsTheTransport() {
        FakeTransport transport = new FakeTransport();
        HolderSession session = HolderSession.create(
                transport, new NoDeadline(), BleMode.PERIPHERAL_SERVER);
        String qr = session.deviceEngagementQr();
        session.start(new RecordingListener());
        establishSession(transport, qr);

        transport.listener.onDisconnected();

        assertTrue(transport.stopped);
    }

    @Test
    public void readerErrorStatusIsReportedAsAnError() {
        FakeTransport transport = new FakeTransport();
        HolderSession session = HolderSession.create(
                transport, new NoDeadline(), BleMode.PERIPHERAL_SERVER);
        String qr = session.deviceEngagementQr();
        RecordingListener listener = new RecordingListener();
        session.start(listener);
        establishSession(transport, qr);

        transport.listener.onMessage(statusFrame(11));

        assertNotNull(listener.error);
        assertFalse(listener.terminated);
    }

    @Test
    public void sessionDataWithNeitherDataNorStatusIsAnError() {
        FakeTransport transport = new FakeTransport();
        HolderSession session = HolderSession.create(
                transport, new NoDeadline(), BleMode.PERIPHERAL_SERVER);
        String qr = session.deviceEngagementQr();
        RecordingListener listener = new RecordingListener();
        session.start(listener);
        establishSession(transport, qr);

        transport.listener.onMessage(CBORObject.NewMap().EncodeToBytes());

        assertNotNull(listener.error);
        assertFalse(listener.terminated);
    }

    @Test
    public void framesArrivingAfterTerminationAreIgnored() {
        FakeTransport transport = new FakeTransport();
        HolderSession session = HolderSession.create(
                transport, new NoDeadline(), BleMode.PERIPHERAL_SERVER);
        String qr = session.deviceEngagementQr();
        RecordingListener listener = new RecordingListener();
        session.start(listener);
        SessionCrypto readerCrypto = establishSession(transport, qr);
        transport.listener.onMessage(statusFrame(20));
        listener.request = null;

        transport.listener.onMessage(
                SessionMessages.data(readerCrypto.encrypt(deviceRequestCbor())));

        assertNull(listener.request);
        assertNull(listener.error);
    }

    @Test
    public void disconnectAfterAnErrorStatusIsNotReportedAsACleanTermination() {
        FakeTransport transport = new FakeTransport();
        HolderSession session = HolderSession.create(
                transport, new NoDeadline(), BleMode.PERIPHERAL_SERVER);
        String qr = session.deviceEngagementQr();
        RecordingListener listener = new RecordingListener();
        session.start(listener);
        establishSession(transport, qr);
        transport.listener.onMessage(statusFrame(11));

        transport.listener.onDisconnected();

        assertNotNull(listener.error);
        assertFalse(listener.terminated);
    }

    @Test
    public void malformedStatusClearsTheSessionBeforeReportingTheError() {
        FakeTransport transport = new FakeTransport();
        HolderSession session = HolderSession.create(
                transport, new NoDeadline(), BleMode.PERIPHERAL_SERVER);
        String qr = session.deviceEngagementQr();
        RecordingListener listener = new RecordingListener();
        session.start(listener);
        SessionCrypto readerCrypto = establishSession(transport, qr);
        CBORObject textStatus = CBORObject.NewMap();
        textStatus.Add("status", "11");

        transport.listener.onMessage(textStatus.EncodeToBytes());

        assertNotNull(listener.error);
        listener.request = null;
        transport.listener.onMessage(
                SessionMessages.data(readerCrypto.encrypt(deviceRequestCbor())));
        assertNull(listener.request);
    }

    @Test
    public void statusCarriedAlongsideDataStillTerminatesTheSession() {
        FakeTransport transport = new FakeTransport();
        HolderSession session = HolderSession.create(
                transport, new NoDeadline(), BleMode.PERIPHERAL_SERVER);
        String qr = session.deviceEngagementQr();
        RecordingListener listener = new RecordingListener();
        session.start(listener);
        SessionCrypto readerCrypto = establishSession(transport, qr);
        listener.request = null;

        CBORObject frame = CBORObject.NewMap();
        frame.Add("data", readerCrypto.encrypt(deviceRequestCbor()));
        frame.Add("status", 20);
        transport.listener.onMessage(frame.EncodeToBytes());

        assertNotNull(listener.request);
        assertTrue(listener.terminated);
        assertNull(listener.error);
    }

    @Test
    public void establishmentArrivingAfterTerminationIsIgnored() {
        FakeTransport transport = new FakeTransport();
        HolderSession session = HolderSession.create(
                transport, new NoDeadline(), BleMode.PERIPHERAL_SERVER);
        String qr = session.deviceEngagementQr();
        RecordingListener listener = new RecordingListener();
        session.start(listener);
        session.terminate();
        listener.terminated = false;

        establishSession(transport, qr);

        assertNull(listener.request);
        assertFalse(listener.connected);
    }

    @Test
    public void undecryptableFrameEndsTheSession() {
        FakeTransport transport = new FakeTransport();
        HolderSession session = HolderSession.create(
                transport, new NoDeadline(), BleMode.PERIPHERAL_SERVER);
        String qr = session.deviceEngagementQr();
        RecordingListener listener = new RecordingListener();
        session.start(listener);
        SessionCrypto readerCrypto = establishSession(transport, qr);
        listener.request = null;

        transport.listener.onMessage(SessionMessages.data(new byte[]{1, 2, 3, 4}));

        assertNotNull(listener.error);
        listener.error = null;
        transport.listener.onMessage(
                SessionMessages.data(readerCrypto.encrypt(deviceRequestCbor())));
        assertNull(listener.request);
        assertNull(listener.error);
    }

    @Test
    public void undecodableDeviceRequestEndsTheSession() {
        FakeTransport transport = new FakeTransport();
        HolderSession session = HolderSession.create(
                transport, new NoDeadline(), BleMode.PERIPHERAL_SERVER);
        String qr = session.deviceEngagementQr();
        RecordingListener listener = new RecordingListener();
        session.start(listener);
        SessionCrypto readerCrypto = establishSession(transport, qr);
        listener.request = null;

        transport.listener.onMessage(
                SessionMessages.data(readerCrypto.encrypt(new byte[]{(byte) 0xFF})));

        assertNotNull(listener.error);
        listener.error = null;
        transport.listener.onMessage(
                SessionMessages.data(readerCrypto.encrypt(deviceRequestCbor())));
        assertNull(listener.request);
        assertNull(listener.error);
    }

    @Test
    public void dataCarriedWithAnErrorStatusIsNeverDelivered() {
        FakeTransport transport = new FakeTransport();
        HolderSession session = HolderSession.create(
                transport, new NoDeadline(), BleMode.PERIPHERAL_SERVER);
        String qr = session.deviceEngagementQr();
        RecordingListener listener = new RecordingListener();
        session.start(listener);
        SessionCrypto readerCrypto = establishSession(transport, qr);
        listener.request = null;

        CBORObject frame = CBORObject.NewMap();
        frame.Add("data", readerCrypto.encrypt(deviceRequestCbor()));
        frame.Add("status", 10);
        transport.listener.onMessage(frame.EncodeToBytes());

        assertNull(listener.request);
        assertNotNull(listener.error);
        assertFalse(listener.terminated);
    }
}
