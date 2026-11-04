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

package org.omnione.did.proximity.sdk.reader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import androidx.annotation.NonNull;

import com.upokecenter.cbor.CBORObject;

import org.junit.Test;
import org.omnione.did.proximity.sdk.core.ProximityException;
import org.omnione.did.proximity.sdk.core.cbor.CoseKeys;
import org.omnione.did.proximity.sdk.core.engagement.DeviceEngagement;
import org.omnione.did.proximity.sdk.core.engagement.DeviceRetrievalMethod;
import org.omnione.did.proximity.sdk.core.engagement.EngagementCodec;
import org.omnione.did.proximity.sdk.core.engagement.SessionTranscripts;
import org.omnione.did.proximity.sdk.core.engagement.TransportMode;
import org.omnione.did.proximity.sdk.core.engagement.TransportPreference;
import org.omnione.did.proximity.sdk.core.engagement.UnsupportedTransportException;
import org.omnione.did.proximity.sdk.core.engagement.WifiOptions;
import org.omnione.did.proximity.sdk.core.message.ItemsRequest;
import org.omnione.did.proximity.sdk.core.session.SessionCrypto;
import org.omnione.did.proximity.sdk.core.session.SessionKeys;
import org.omnione.did.proximity.sdk.core.session.SessionMessages;
import org.omnione.did.proximity.sdk.core.transport.Transport;
import org.omnione.did.proximity.sdk.core.util.NoDeadline;

import java.lang.reflect.Field;
import java.security.KeyPair;
import java.security.interfaces.ECPublicKey;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class ReaderSessionErrorPathTest {

    private static final String DOC_TYPE = "eu.europa.ec.eudi.pid.1";

    private static final class FakeTransport implements Transport {
        final List<byte[]> sent = new ArrayList<>();
        final List<String> timeline = new ArrayList<>();
        Listener listener;
        RuntimeException sendFailure;

        @Override
        public void start(@NonNull Listener listener) {
            this.listener = listener;
        }

        @Override
        public void send(@NonNull byte[] message) {
            if (sendFailure != null) {
                timeline.add("send-rejected");
                throw sendFailure;
            }
            sent.add(message);
            timeline.add("send");
        }

        @Override
        public void stop() {
            timeline.add("stop");
        }
    }

    private static final class RecordingListener implements ReaderSession.Listener {
        boolean connected;
        final List<ProximityResponse> responses = new ArrayList<>();
        ProximityException error;
        boolean terminated;

        @Override public void onMdocConnected() { connected = true; }
        @Override public void onResponse(@NonNull ProximityResponse response) { responses.add(response); }
        @Override public void onError(@NonNull ProximityException e) { error = e; }
        @Override public void onTerminated() { terminated = true; }
    }

    private static ItemsRequest request() {
        Map<String, Boolean> elements = new LinkedHashMap<>();
        elements.put("family_name", false);
        Map<String, Map<String, Boolean>> nameSpaces = new LinkedHashMap<>();
        nameSpaces.put(DOC_TYPE, elements);
        return ItemsRequest.of(DOC_TYPE, nameSpaces);
    }

    private static final class EstablishedReader {
        final FakeTransport transport;
        final RecordingListener listener;
        final ReaderSession reader;
        final byte[] engagementBytes;
        final KeyPair devicePair;

        EstablishedReader() {
            transport = new FakeTransport();
            devicePair = CoseKeys.generateEphemeral();
            UUID serviceUuid = UUID.randomUUID();
            engagementBytes = DeviceEngagement.forQr(
                    (ECPublicKey) devicePair.getPublic(), serviceUuid).encode();
            String qr = EngagementCodec.encode(engagementBytes);

            listener = new RecordingListener();
            reader = ReaderSession.create(transport, new NoDeadline());
            reader.start(qr, request(), listener);
            transport.listener.onConnected();
        }

        @NonNull
        SessionCrypto matchingDeviceCrypto() {
            SessionMessages.Establishment establishment =
                    SessionMessages.parseEstablishment(transport.sent.get(0));
            ECPublicKey readerKey = CoseKeys.toPublicKey(establishment.eReaderKeyBytes);
            byte[] transcript = SessionTranscripts.forQr(engagementBytes, establishment.eReaderKeyBytes);
            return SessionCrypto.forDevice(
                    SessionKeys.derive(devicePair.getPrivate(), readerKey, transcript));
        }
    }

    @Test
    public void terminateSendsTerminationAndNotifiesTheListener() {
        EstablishedReader session = new EstablishedReader();
        int sentBefore = session.transport.sent.size();

        session.reader.terminate();

        assertEquals(sentBefore + 1, session.transport.sent.size());
        assertEquals(SessionMessages.STATUS_SESSION_TERMINATION,
                statusOf(session.transport.sent.get(sentBefore)));
        assertTrue(session.listener.terminated);
    }

    @Test
    public void closeWithoutNotifyingTellsTheMdocNothing() {
        EstablishedReader session = new EstablishedReader();
        int sentBefore = session.transport.sent.size();

        session.reader.closeWithoutNotifying();

        assertEquals("the quiet path must not put a frame on the wire",
                sentBefore, session.transport.sent.size());
        assertFalse(session.listener.terminated);
        assertNull(session.listener.error);
    }

    private static int statusOf(@NonNull byte[] frame) {
        return CBORObject.DecodeFromBytes(frame).get("status").AsInt32Value();
    }

    @Test
    public void unauthenticatedCiphertextSendsSessionEncryptionErrorBeforeStopping() {
        EstablishedReader session = new EstablishedReader();

        session.transport.listener.onMessage(SessionMessages.data(new byte[]{1, 2, 3, 4}));

        assertNotNull(session.listener.error);
        assertEquals(Arrays.asList("send", "send", "stop"), session.transport.timeline);
        assertEquals(SessionMessages.STATUS_SESSION_ENCRYPTION_ERROR,
                statusOf(session.transport.sent.get(1)));
    }

    @Test
    public void malformedButAuthenticatedResponseSendsCborDecodingErrorBeforeStopping() {
        EstablishedReader session = new EstablishedReader();
        SessionCrypto deviceCrypto = session.matchingDeviceCrypto();

        session.transport.listener.onMessage(
                SessionMessages.data(deviceCrypto.encrypt(new byte[]{(byte) 0xFF})));

        assertNotNull(session.listener.error);
        assertEquals(Arrays.asList("send", "send", "stop"), session.transport.timeline);
        assertEquals(SessionMessages.STATUS_CBOR_DECODING_ERROR,
                statusOf(session.transport.sent.get(1)));
    }

    @Test
    public void terminateCalledTwiceDoesNotThrow() {
        EstablishedReader session = new EstablishedReader();

        session.reader.terminate();
        session.reader.terminate();

        assertTrue(session.listener.terminated);
    }

    @Test
    public void messagesArrivingAfterTerminationAreIgnored() {
        EstablishedReader session = new EstablishedReader();
        SessionCrypto deviceCrypto = session.matchingDeviceCrypto();
        session.reader.terminate();

        session.transport.listener.onMessage(
                SessionMessages.data(deviceCrypto.encrypt(new byte[]{(byte) 0xFF})));

        assertNull(session.listener.error);
        assertTrue(session.listener.responses.isEmpty());
    }

    @Test
    public void malformedFrameArrivingAfterTerminationIsIgnored() {
        EstablishedReader session = new EstablishedReader();
        session.reader.terminate();
        int sentBefore = session.transport.sent.size();

        session.transport.listener.onMessage(new byte[]{(byte) 0xFF});

        assertNull(session.listener.error);
        assertEquals(sentBefore, session.transport.sent.size());
    }

    @Test
    public void frameThatFailsResponseParsingAfterTerminationIsIgnored() {
        EstablishedReader session = new EstablishedReader();
        SessionCrypto deviceCrypto = session.matchingDeviceCrypto();
        session.reader.terminate();
        int sentBefore = session.transport.sent.size();

        session.transport.listener.onMessage(
                SessionMessages.data(deviceCrypto.encrypt(new byte[]{(byte) 0xFF})));

        assertNull(session.listener.error);
        assertEquals(sentBefore, session.transport.sent.size());
    }

    @Test
    public void terminateBlocksUntilAnInFlightCriticalSectionReleasesTheLock() throws Exception {
        EstablishedReader session = new EstablishedReader();
        Object stateLock = fieldValue(session.reader, "stateLock");
        Object crypto = fieldValue(session.reader, "crypto");
        assertNotNull(crypto);
        assertFalse(isAllZero((byte[]) fieldValue(crypto, "encryptKey")));

        CountDownLatch holderInside = new CountDownLatch(1);
        CountDownLatch releaseHolder = new CountDownLatch(1);
        Thread criticalSectionHolder = new Thread(() -> {
            synchronized (stateLock) {
                holderInside.countDown();
                try {
                    releaseHolder.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        criticalSectionHolder.start();
        assertTrue(holderInside.await(2, TimeUnit.SECONDS));

        Thread terminator = new Thread(session.reader::terminate);
        terminator.start();

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (terminator.getState() != Thread.State.BLOCKED) {
            if (System.nanoTime() > deadline) {
                fail("terminate() never blocked waiting for stateLock");
            }
            Thread.sleep(5);
        }

        assertFalse(isAllZero((byte[]) fieldValue(crypto, "encryptKey")));

        releaseHolder.countDown();
        criticalSectionHolder.join(2000);
        terminator.join(2000);

        assertTrue(isAllZero((byte[]) fieldValue(crypto, "encryptKey")));
        assertTrue(isAllZero((byte[]) fieldValue(crypto, "decryptKey")));
    }

    @Test
    public void terminateCompletesWhenTheTransportRejectsTheTerminationFrame() {
        EstablishedReader session = new EstablishedReader();
        session.transport.sendFailure = new ProximityException("no mdoc is connected");

        session.reader.terminate();

        assertTrue(session.listener.terminated);
        assertEquals(Arrays.asList("send", "send-rejected", "stop"), session.transport.timeline);
    }

    @Test
    public void startWithANonMdocQrStringIsReportedThroughOnError() {
        FakeTransport transport = new FakeTransport();
        RecordingListener listener = new RecordingListener();

        ReaderSession.create(transport, new NoDeadline())
                .start("WIFI:S:cafe;T:WPA;P:secret;;", request(), listener);

        assertNotNull(listener.error);
        assertNull(transport.listener);
        assertTrue(listener.responses.isEmpty());
    }

    @Test
    public void startWithAMalformedEngagementLeavesTheSessionTerminated() {
        FakeTransport transport = new FakeTransport();
        RecordingListener listener = new RecordingListener();
        ReaderSession reader = ReaderSession.create(transport, new NoDeadline());

        reader.start(EngagementCodec.encode(new byte[]{0x00, 0x01, 0x02}), request(), listener);

        assertNotNull(listener.error);
        assertEquals(Arrays.asList("stop"), transport.timeline);
    }

    @Test
    public void transportErrorsArrivingAfterTerminationAreNotReported() {
        EstablishedReader session = new EstablishedReader();
        Transport.Listener wire = session.transport.listener;
        session.reader.terminate();

        wire.onError(new ProximityException("a late transport failure"));

        assertNull(session.listener.error);
    }

    @Test
    public void holderSessionEncryptionErrorStatusIsReportedAsAnError() {
        EstablishedReader session = new EstablishedReader();

        session.transport.listener.onMessage(
                SessionMessages.status(SessionMessages.STATUS_SESSION_ENCRYPTION_ERROR));

        assertNotNull(session.listener.error);
        assertTrue(session.listener.terminated);
    }

    @Test
    public void holderCborDecodingErrorStatusIsReportedAsAnError() {
        EstablishedReader session = new EstablishedReader();

        session.transport.listener.onMessage(
                SessionMessages.status(SessionMessages.STATUS_CBOR_DECODING_ERROR));

        assertNotNull(session.listener.error);
        assertTrue(session.listener.terminated);
    }

    @Test
    public void holderTerminationStatusIsReportedAsACleanTermination() {
        EstablishedReader session = new EstablishedReader();

        session.transport.listener.onMessage(SessionMessages.termination());

        assertNull(session.listener.error);
        assertTrue(session.listener.terminated);
    }

    private static String bleAndWifiQr() {
        KeyPair devicePair = CoseKeys.generateEphemeral();
        return EngagementCodec.encode(DeviceEngagement.forQr(
                (ECPublicKey) devicePair.getPublic(), Arrays.asList(
                        DeviceRetrievalMethod.blePeripheralServerMode(UUID.randomUUID()),
                        DeviceRetrievalMethod.wifiAware(WifiOptions.of(new byte[]{0x14}))))
                .encode());
    }

    @Test
    public void aWifiAwarePreferenceSelectsTheWifiMethodAndStartsTheTransport() {
        FakeTransport transport = new FakeTransport();
        RecordingListener listener = new RecordingListener();
        ReaderSession reader = ReaderSession.create(transport, new NoDeadline(),
                TransportPreference.of(TransportMode.WIFI_AWARE));

        reader.start(bleAndWifiQr(), request(), listener);
        transport.listener.onConnected();

        assertNull(listener.error);
        assertTrue(listener.connected);
        assertEquals(1, transport.sent.size());
    }

    @Test
    public void aWifiOnlyPreferenceAgainstABleOnlyQrIsAnUnsupportedTransport() {
        FakeTransport transport = new FakeTransport();
        RecordingListener listener = new RecordingListener();
        ReaderSession reader = ReaderSession.create(transport, new NoDeadline(),
                TransportPreference.of(TransportMode.WIFI_AWARE));
        String qr = EngagementCodec.encode(DeviceEngagement.forQr(
                (ECPublicKey) CoseKeys.generateEphemeral().getPublic(), UUID.randomUUID()).encode());

        reader.start(qr, request(), listener);

        assertTrue(listener.error instanceof UnsupportedTransportException);
        assertNull(transport.listener);
    }

    @Test
    public void theDefaultPreferenceIgnoresTheWifiMethod() {
        FakeTransport transport = new FakeTransport();
        RecordingListener listener = new RecordingListener();
        ReaderSession reader = ReaderSession.create(transport, new NoDeadline());

        reader.start(bleAndWifiQr(), request(), listener);
        transport.listener.onConnected();

        assertNull(listener.error);
        assertTrue(listener.connected);
    }

    private static boolean isAllZero(@NonNull byte[] value) {
        for (byte b : value) {
            if (b != 0) {
                return false;
            }
        }
        return true;
    }

    private static Object fieldValue(@NonNull Object target, @NonNull String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }
}
