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
import static org.junit.Assert.assertTrue;

import androidx.annotation.NonNull;

import com.upokecenter.cbor.CBORObject;

import org.junit.Test;
import org.omnione.did.proximity.sdk.core.ProximityException;
import org.omnione.did.proximity.sdk.core.cbor.CoseKeys;
import org.omnione.did.proximity.sdk.core.engagement.DeviceEngagement;
import org.omnione.did.proximity.sdk.core.engagement.EngagementCodec;
import org.omnione.did.proximity.sdk.core.engagement.SessionTranscripts;
import org.omnione.did.proximity.sdk.core.message.ItemsRequest;
import org.omnione.did.proximity.sdk.core.session.SessionCrypto;
import org.omnione.did.proximity.sdk.core.session.SessionKeys;
import org.omnione.did.proximity.sdk.core.session.SessionMessages;
import org.omnione.did.proximity.sdk.core.transport.Transport;
import org.omnione.did.proximity.sdk.core.util.Deadline;

import java.security.KeyPair;
import java.security.interfaces.ECPublicKey;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class ReaderSessionTerminateRaceTest {

    private static final String DOC_TYPE = "eu.europa.ec.eudi.pid.1";
    private static final Deadline IDLE = (delayMs, onExpiry) -> () -> { };

    private static final class FakeTransport implements Transport {
        final List<byte[]> sent = new ArrayList<>();
        final List<String> timeline = new ArrayList<>();
        Listener listener;

        @Override
        public void start(@NonNull Listener listener) {
            this.listener = listener;
            timeline.add("start");
        }

        @Override
        public void send(@NonNull byte[] message) {
            sent.add(message);
            timeline.add("send");
        }

        @Override
        public void stop() {
            timeline.add("stop");
        }
    }

    private static final class OnResponseClaim implements Deadline {
        Runnable action;

        @Override
        public Cancellable schedule(long delayMs, @NonNull Runnable onExpiry) {
            if (delayMs != ReaderSession.RESPONSE_TIMEOUT_MS) {
                return () -> { };
            }
            boolean[] fired = new boolean[1];
            return () -> {
                if (!fired[0]) {
                    fired[0] = true;
                    action.run();
                }
            };
        }
    }

    private static final class CountingListener implements ReaderSession.Listener {
        int connected;
        int responses;
        int errors;
        int terminated;

        @Override public void onMdocConnected() { connected++; }
        @Override public void onResponse(@NonNull ProximityResponse response) { responses++; }
        @Override public void onError(@NonNull ProximityException e) { errors++; }
        @Override public void onTerminated() { terminated++; }
    }

    private static final class Session {
        final FakeTransport transport = new FakeTransport();
        final CountingListener listener = new CountingListener();
        final KeyPair devicePair = CoseKeys.generateEphemeral();
        final byte[] engagementBytes = DeviceEngagement.forQr(
                (ECPublicKey) devicePair.getPublic(), UUID.randomUUID()).encode();
        final ReaderSession reader;

        Session(@NonNull Deadline deadline) {
            reader = ReaderSession.create(transport, deadline);
        }

        Session connect() {
            reader.start(EngagementCodec.encode(engagementBytes), request(), listener);
            transport.listener.onConnected();
            return this;
        }

        @NonNull
        byte[] encryptedResponse() {
            SessionMessages.Establishment establishment =
                    SessionMessages.parseEstablishment(transport.sent.get(0));
            ECPublicKey readerKey = CoseKeys.toPublicKey(establishment.eReaderKeyBytes);
            byte[] transcript = SessionTranscripts.forQr(
                    engagementBytes, establishment.eReaderKeyBytes);
            SessionCrypto device = SessionCrypto.forDevice(
                    SessionKeys.derive(devicePair.getPrivate(), readerKey, transcript));
            return device.encrypt(DeviceResponseFixture.of(DOC_TYPE, "family_name", "Kim"));
        }
    }

    private static Session terminatingDuringVerification() {
        OnResponseClaim deadline = new OnResponseClaim();
        Session session = new Session(deadline);
        deadline.action = session.reader::terminate;
        return session.connect();
    }

    @Test
    public void aDisconnectDuringVerificationStillDeliversTheResponse() {
        OnResponseClaim deadline = new OnResponseClaim();
        Session session = new Session(deadline).connect();
        Transport.Listener wire = session.transport.listener;
        deadline.action = wire::onDisconnected;
        CBORObject frame = CBORObject.NewMap();
        frame.Add("data", session.encryptedResponse());
        frame.Add("status", SessionMessages.STATUS_SESSION_TERMINATION);

        wire.onMessage(frame.EncodeToBytes());

        assertEquals(1, session.listener.responses);
        assertEquals(1, session.listener.terminated);
        assertEquals(0, session.listener.errors);
    }

    @Test
    public void terminateDuringVerificationDropsTheResponse() {
        Session session = terminatingDuringVerification();

        session.transport.listener.onMessage(SessionMessages.data(session.encryptedResponse()));

        assertEquals(0, session.listener.responses);
        assertEquals(1, session.listener.terminated);
        assertEquals(0, session.listener.errors);
    }

    @Test
    public void terminateDuringVerificationOfAResponseWithStatusDropsTheResponse() {
        Session session = terminatingDuringVerification();
        CBORObject frame = CBORObject.NewMap();
        frame.Add("data", session.encryptedResponse());
        frame.Add("status", SessionMessages.STATUS_SESSION_TERMINATION);

        session.transport.listener.onMessage(frame.EncodeToBytes());

        assertEquals(0, session.listener.responses);
        assertEquals(1, session.listener.terminated);
        assertEquals(0, session.listener.errors);
    }

    @Test
    public void aResponseWithoutTerminateIsDeliveredAndTerminatesOnce() {
        Session session = new Session(IDLE).connect();

        session.transport.listener.onMessage(SessionMessages.data(session.encryptedResponse()));

        assertEquals(1, session.listener.responses);
        assertEquals(1, session.listener.terminated);
    }

    @Test
    public void terminateTwiceNotifiesOnce() {
        Session session = new Session(IDLE).connect();

        session.reader.terminate();
        session.reader.terminate();

        assertEquals(1, session.listener.terminated);
    }

    @Test
    public void terminateAfterAnErrorDoesNotNotify() {
        Session session = new Session(IDLE).connect();
        session.transport.listener.onMessage(SessionMessages.data(new byte[]{1, 2, 3, 4}));

        session.reader.terminate();

        assertEquals(1, session.listener.errors);
        assertEquals(0, session.listener.terminated);
    }

    @Test
    public void terminateAfterAPeerTerminationDoesNotNotifyAgain() {
        Session session = new Session(IDLE).connect();
        session.transport.listener.onMessage(SessionMessages.termination());

        session.reader.terminate();

        assertEquals(1, session.listener.terminated);
    }

    @Test
    public void disconnectAfterTerminateDoesNotNotifyAgain() {
        Session session = new Session(IDLE).connect();
        Transport.Listener wire = session.transport.listener;

        session.reader.terminate();
        wire.onDisconnected();

        assertEquals(1, session.listener.terminated);
    }

    @Test
    public void terminateBeforeStartLeavesTheTransportUnstartedAndClosed() {
        Session session = new Session(IDLE);

        session.reader.terminate();
        session.reader.start(EngagementCodec.encode(session.engagementBytes), request(),
                session.listener);

        assertFalse(session.transport.timeline.contains("start"));
        assertFalse(session.transport.timeline.contains("send"));
        assertTrue(session.transport.timeline.contains("stop"));
        assertEquals(0, session.listener.connected);
        assertEquals(0, session.listener.errors);
    }

    private static ItemsRequest request() {
        Map<String, Boolean> elements = new LinkedHashMap<>();
        elements.put("family_name", false);
        Map<String, Map<String, Boolean>> nameSpaces = new LinkedHashMap<>();
        nameSpaces.put(DOC_TYPE, elements);
        return ItemsRequest.of(DOC_TYPE, nameSpaces);
    }
}
