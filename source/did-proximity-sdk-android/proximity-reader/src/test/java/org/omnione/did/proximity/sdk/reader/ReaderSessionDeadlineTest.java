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
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import androidx.annotation.NonNull;

import org.junit.Test;
import org.omnione.did.proximity.sdk.core.ProximityException;
import org.omnione.did.proximity.sdk.core.cbor.CoseKeys;
import org.omnione.did.proximity.sdk.core.engagement.DeviceEngagement;
import org.omnione.did.proximity.sdk.core.engagement.EngagementCodec;
import org.omnione.did.proximity.sdk.core.message.ItemsRequest;
import org.omnione.did.proximity.sdk.core.session.SessionMessages;
import org.omnione.did.proximity.sdk.core.transport.Transport;
import org.omnione.did.proximity.sdk.core.util.Deadline;

import java.security.interfaces.ECPublicKey;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class ReaderSessionDeadlineTest {

    private static final String DOC_TYPE = "eu.europa.ec.eudi.pid.1";

    private static final class FakeTransport implements Transport {
        final List<String> timeline = new ArrayList<>();
        Listener listener;

        @Override
        public void start(@NonNull Listener listener) {
            this.listener = listener;
            timeline.add("start");
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

    private static final class ManualDeadline implements Deadline {
        final List<Long> delays = new ArrayList<>();
        final List<Runnable> expiries = new ArrayList<>();
        long scheduledDelayMs = -1;
        int scheduledCount;
        int cancelled;

        @Override
        public Cancellable schedule(long delayMs, @NonNull Runnable onExpiry) {
            this.delays.add(delayMs);
            this.expiries.add(onExpiry);
            this.scheduledDelayMs = delayMs;
            this.scheduledCount++;
            return () -> cancelled++;
        }

        void fire(long delayMs) {
            int index = delays.lastIndexOf(delayMs);
            assertTrue("no deadline scheduled at " + delayMs + "ms", index >= 0);
            expiries.get(index).run();
        }
    }

    private static final class RecordingListener implements ReaderSession.Listener {
        boolean connected;
        ProximityException error;
        int errorCount;
        boolean terminated;

        @Override public void onMdocConnected() { connected = true; }
        @Override public void onResponse(@NonNull ProximityResponse response) { }
        @Override public void onError(@NonNull ProximityException e) { error = e; errorCount++; }
        @Override public void onTerminated() { terminated = true; }
    }

    @Test
    public void theDeadlineStartsWithTheTransport() {
        ManualDeadline deadline = new ManualDeadline();
        FakeTransport transport = new FakeTransport();

        ReaderSession.create(transport, deadline).start(qr(), request(), new RecordingListener());

        assertEquals(30_000L, ReaderSession.CONNECT_TIMEOUT_MS);
        assertEquals(ReaderSession.CONNECT_TIMEOUT_MS, deadline.scheduledDelayMs);
        assertTrue(transport.timeline.contains("start"));
    }

    @Test
    public void terminateLetsTheTerminationWriteFlushBeforeStopping() {
        ManualDeadline deadline = new ManualDeadline();
        FakeTransport transport = new FakeTransport();
        ReaderSession reader = ReaderSession.create(transport, deadline);
        reader.start(qr(), request(), new RecordingListener());
        transport.listener.onConnected();

        reader.terminate();

        assertFalse(transport.timeline.contains("stop"));
        assertEquals(ReaderSession.TERMINATION_GRACE_MS, deadline.scheduledDelayMs);

        deadline.fire(ReaderSession.TERMINATION_GRACE_MS);

        assertTrue(transport.timeline.contains("stop"));
    }

    @Test
    public void closingAfterTerminateStopsWithoutWaitingForTheGrace() {
        ManualDeadline deadline = new ManualDeadline();
        FakeTransport transport = new FakeTransport();
        ReaderSession reader = ReaderSession.create(transport, deadline);
        reader.start(qr(), request(), new RecordingListener());
        transport.listener.onConnected();
        reader.terminate();
        int cancelledBefore = deadline.cancelled;

        reader.closeWithoutNotifying();

        assertTrue(transport.timeline.contains("stop"));
        assertEquals(cancelledBefore + 1, deadline.cancelled);
    }

    @Test
    public void connectingCancelsTheDeadline() {
        ManualDeadline deadline = new ManualDeadline();
        FakeTransport transport = new FakeTransport();
        RecordingListener listener = new RecordingListener();

        ReaderSession.create(transport, deadline).start(qr(), request(), listener);
        transport.listener.onConnected();

        assertEquals(1, deadline.cancelled);
        assertTrue(listener.connected);
        assertNull(listener.error);
    }

    @Test
    public void expiryFailsTheSessionAndStopsTheTransport() {
        ManualDeadline deadline = new ManualDeadline();
        FakeTransport transport = new FakeTransport();
        RecordingListener listener = new RecordingListener();

        ReaderSession.create(transport, deadline).start(qr(), request(), listener);
        deadline.fire(ReaderSession.CONNECT_TIMEOUT_MS);

        assertNotNull(listener.error);
        assertEquals(ProximityException.Reason.ENGAGEMENT_TIMEOUT, listener.error.reason());
        assertTrue(transport.timeline.contains("stop"));
        assertFalse(listener.connected);
    }

    @Test
    public void expiryAfterConnectingIsIgnored() {
        ManualDeadline deadline = new ManualDeadline();
        FakeTransport transport = new FakeTransport();
        RecordingListener listener = new RecordingListener();

        ReaderSession.create(transport, deadline).start(qr(), request(), listener);
        transport.listener.onConnected();
        deadline.fire(ReaderSession.CONNECT_TIMEOUT_MS);

        assertNull(listener.error);
    }

    @Test
    public void expiryThatRacesCancellationIsIgnored() {
        ManualDeadline deadline = new ManualDeadline();
        FakeTransport transport = new FakeTransport();
        RecordingListener listener = new RecordingListener();

        ReaderSession.create(transport, deadline).start(qr(), request(), listener);
        transport.listener.onConnected();
        assertEquals(1, deadline.cancelled);

        deadline.fire(ReaderSession.CONNECT_TIMEOUT_MS);

        assertNull(listener.error);
        assertFalse(transport.timeline.contains("stop"));
    }

    @Test
    public void onlyTheFirstConnectionClaimsTheDeadline() {
        ManualDeadline deadline = new ManualDeadline();
        FakeTransport transport = new FakeTransport();
        RecordingListener listener = new RecordingListener();

        ReaderSession.create(transport, deadline).start(qr(), request(), listener);
        transport.listener.onConnected();
        listener.connected = false;
        transport.listener.onConnected();

        assertFalse(listener.connected);
        assertNull(listener.error);
    }

    @Test
    public void expiryAfterAnEarlierErrorDoesNotDeliverASecondError() {
        ManualDeadline deadline = new ManualDeadline();
        FakeTransport transport = new FakeTransport();
        RecordingListener listener = new RecordingListener();

        ReaderSession.create(transport, deadline).start(qr(), request(), listener);
        ProximityException original = new ProximityException("advertise permission denied");
        transport.listener.onError(original);
        deadline.fire(ReaderSession.CONNECT_TIMEOUT_MS);

        assertEquals(1, listener.errorCount);
        assertEquals(original, listener.error);
        assertTrue(transport.timeline.contains("stop"));
    }

    @Test
    public void theResponseDeadlineStartsWhenTheEstablishmentIsSent() {
        ManualDeadline deadline = new ManualDeadline();
        FakeTransport transport = new FakeTransport();

        ReaderSession.create(transport, deadline).start(qr(), request(), new RecordingListener());
        assertEquals(1, deadline.scheduledCount);

        transport.listener.onConnected();

        assertEquals(120_000L, ReaderSession.RESPONSE_TIMEOUT_MS);
        assertEquals(2, deadline.scheduledCount);
        assertEquals(ReaderSession.RESPONSE_TIMEOUT_MS, deadline.scheduledDelayMs);
        assertTrue(transport.timeline.contains("send"));
    }

    @Test
    public void theResponseDeadlineFiresIntoOnErrorAndStopsTheTransport() {
        ManualDeadline deadline = new ManualDeadline();
        FakeTransport transport = new FakeTransport();
        RecordingListener listener = new RecordingListener();

        ReaderSession.create(transport, deadline).start(qr(), request(), listener);
        transport.listener.onConnected();

        deadline.fire(ReaderSession.RESPONSE_TIMEOUT_MS);

        assertNotNull(listener.error);
        assertEquals(ProximityException.Reason.RESPONSE_TIMEOUT, listener.error.reason());
        assertTrue(transport.timeline.contains("stop"));
    }

    @Test
    public void aPeerTerminationCancelsTheResponseDeadline() {
        ManualDeadline deadline = new ManualDeadline();
        FakeTransport transport = new FakeTransport();
        RecordingListener listener = new RecordingListener();

        ReaderSession.create(transport, deadline).start(qr(), request(), listener);
        transport.listener.onConnected();
        int cancelledBefore = deadline.cancelled;

        transport.listener.onMessage(SessionMessages.termination());

        assertEquals(cancelledBefore + 1, deadline.cancelled);
        assertTrue(listener.terminated);

        deadline.fire(ReaderSession.RESPONSE_TIMEOUT_MS);

        assertNull(listener.error);
    }

    @Test
    public void startingTwiceIsRejectedAndDoesNotArmASecondDeadline() {
        ManualDeadline deadline = new ManualDeadline();
        FakeTransport transport = new FakeTransport();
        ReaderSession session = ReaderSession.create(transport, deadline);
        session.start(qr(), request(), new RecordingListener());

        assertThrows(ProximityException.class,
                () -> session.start(qr(), request(), new RecordingListener()));

        assertEquals(1, deadline.scheduledCount);
    }

    private static String qr() {
        ECPublicKey key = (ECPublicKey) CoseKeys.generateEphemeral().getPublic();
        return EngagementCodec.encode(
                DeviceEngagement.forQr(key, UUID.randomUUID()).encode());
    }

    private static ItemsRequest request() {
        Map<String, Boolean> elements = new LinkedHashMap<>();
        elements.put("family_name", false);
        Map<String, Map<String, Boolean>> nameSpaces = new LinkedHashMap<>();
        nameSpaces.put(DOC_TYPE, elements);
        return ItemsRequest.of(DOC_TYPE, nameSpaces);
    }
}
