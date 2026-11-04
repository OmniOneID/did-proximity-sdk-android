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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import androidx.annotation.NonNull;

import org.junit.After;
import com.upokecenter.cbor.CBORObject;

import org.junit.Test;
import org.omnione.did.proximity.sdk.core.ProximityException;
import org.omnione.did.proximity.sdk.core.cbor.CborTags;
import org.omnione.did.proximity.sdk.core.engagement.BleMode;
import org.omnione.did.proximity.sdk.core.message.ItemsRequest;
import org.omnione.did.proximity.sdk.core.transport.Transport;
import org.omnione.did.proximity.sdk.core.transport.wifiaware.HttpMdocClientWire;
import org.omnione.did.proximity.sdk.core.transport.wifiaware.HttpMdocServerWire;
import org.omnione.did.proximity.sdk.core.util.NoDeadline;
import org.omnione.did.proximity.sdk.holder.HolderSession;
import org.omnione.did.proximity.sdk.holder.ProximityRequest;

import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class ReaderSessionWifiLoopbackTest {

    private static final String DOC_TYPE = "eu.europa.ec.eudi.pid.1";
    private static final long WAIT_MS = 5_000L;

    private final ExecutorService holderIo = Executors.newSingleThreadExecutor();
    private final ExecutorService readerIo = Executors.newSingleThreadExecutor();

    private final class HolderHttpTransport implements Transport {
        private final PipedInputStream in;
        private final PipedOutputStream out;
        private volatile HttpMdocServerWire wire;

        HolderHttpTransport(PipedInputStream in, PipedOutputStream out) {
            this.in = in;
            this.out = out;
        }

        @Override
        public void start(@NonNull Listener listener) {
            wire = new HttpMdocServerWire(in, out, listener);
            holderIo.execute(() -> {
                listener.onConnected();
                wire.serve();
            });
        }

        @Override
        public void send(@NonNull byte[] message) {
            wire.send(message);
        }

        @Override
        public void stop() {
            wire.close();
        }
    }

    private final class ReaderHttpTransport implements Transport {
        private final PipedInputStream in;
        private final PipedOutputStream out;
        private volatile HttpMdocClientWire wire;

        ReaderHttpTransport(PipedInputStream in, PipedOutputStream out) {
            this.in = in;
            this.out = out;
        }

        @Override
        public void start(@NonNull Listener listener) {
            wire = new HttpMdocClientWire(in, out, "[fe80::1]", readerIo, listener);
            readerIo.execute(listener::onConnected);
        }

        @Override
        public void send(@NonNull byte[] message) {
            wire.send(message);
        }

        @Override
        public void stop() {
            wire.close();
        }
    }

    private static final class HolderEvents implements HolderSession.Listener {
        final List<ProximityRequest> requests = new ArrayList<>();
        final CountDownLatch requested = new CountDownLatch(1);
        final CountDownLatch terminated = new CountDownLatch(1);
        volatile ProximityException error;
        volatile HolderSession session;
        volatile boolean respond = true;

        @Override
        public void onReaderConnected() {
        }

        @Override
        public void onRequest(@NonNull ProximityRequest request) {
            requests.add(request);
            requested.countDown();
            if (respond) {
                session.sendResponse(DeviceResponseFixture.of(DOC_TYPE, "family_name", "Kim"));
            }
        }

        @Override
        public void onResponseSent() {
        }

        @Override
        public void onError(@NonNull ProximityException e) {
            error = e;
        }

        @Override
        public void onTerminated() {
            terminated.countDown();
        }
    }

    private static final class ReaderEvents implements ReaderSession.Listener {
        final List<ProximityResponse> responses = new ArrayList<>();
        final CountDownLatch responded = new CountDownLatch(1);
        final CountDownLatch terminated = new CountDownLatch(1);
        volatile ProximityException error;

        @Override
        public void onMdocConnected() {
        }

        @Override
        public void onResponse(@NonNull ProximityResponse response) {
            responses.add(response);
            responded.countDown();
        }

        @Override
        public void onError(@NonNull ProximityException e) {
            error = e;
        }

        @Override
        public void onTerminated() {
            terminated.countDown();
        }
    }

    private HolderSession holder;
    private ReaderSession reader;
    private final HolderEvents holderEvents = new HolderEvents();
    private final ReaderEvents readerEvents = new ReaderEvents();

    private void engage() throws IOException {
        PipedInputStream holderIn = new PipedInputStream(1 << 16);
        PipedOutputStream readerOut = new PipedOutputStream(holderIn);
        PipedInputStream readerIn = new PipedInputStream(1 << 16);
        PipedOutputStream holderOut = new PipedOutputStream(readerIn);

        holder = HolderSession.create(new HolderHttpTransport(holderIn, holderOut),
                new NoDeadline(), BleMode.PERIPHERAL_SERVER);
        holderEvents.session = holder;
        holder.start(holderEvents);

        reader = ReaderSession.create(new ReaderHttpTransport(readerIn, readerOut),
                new NoDeadline());
        reader.start(holder.deviceEngagementQr(), request(), readerEvents);
    }

    @After
    public void tearDown() {
        if (reader != null) {
            reader.closeWithoutNotifying();
        }
        if (holder != null) {
            holder.closeWithoutNotifying();
        }
        holderIo.shutdownNow();
        readerIo.shutdownNow();
    }

    private static ItemsRequest request() {
        Map<String, Boolean> elements = new LinkedHashMap<>();
        elements.put("family_name", false);
        Map<String, Map<String, Boolean>> nameSpaces = new LinkedHashMap<>();
        nameSpaces.put(DOC_TYPE, elements);
        return ItemsRequest.of(DOC_TYPE, nameSpaces);
    }

    private static void await(CountDownLatch latch) throws InterruptedException {
        assertTrue("timed out", latch.await(WAIT_MS, TimeUnit.MILLISECONDS));
    }

    @Test
    public void aRequestAndResponseRoundTripOverHttp() throws Exception {
        engage();

        await(holderEvents.requested);
        await(readerEvents.responded);

        assertNull(holderEvents.error);
        assertNull(readerEvents.error);
        assertEquals(DOC_TYPE, holderEvents.requests.get(0).docType);
        assertEquals("Kim",
                readerEvents.responses.get(0).documents.get(0).flatClaims().get("family_name"));
        assertArrayEquals(
                CborTags.untag(CBORObject.DecodeFromBytes(
                        holderEvents.requests.get(0).sessionTranscript)),
                readerEvents.responses.get(0).sessionTranscript);
    }

    @Test
    public void readerTerminationReachesTheHolderAsAPost() throws Exception {
        engage();
        await(readerEvents.responded);

        reader.terminate();

        await(readerEvents.terminated);
        await(holderEvents.terminated);
        assertNull(holderEvents.error);
        assertNull(readerEvents.error);
    }

    @Test
    public void holderTerminationTravelsInTheResponseBody() throws Exception {
        holderEvents.respond = false;
        engage();
        await(holderEvents.requested);

        holder.terminate();

        await(holderEvents.terminated);
        await(readerEvents.terminated);
        assertNull(holderEvents.error);
        assertNull(readerEvents.error);
        assertTrue(readerEvents.responses.isEmpty());
    }

    @Test
    public void closingTheHolderWithAPendingRequestTerminatesTheReader() throws Exception {
        holderEvents.respond = false;
        engage();
        await(holderEvents.requested);

        holder.closeWithoutNotifying();

        await(readerEvents.terminated);
        assertNull(readerEvents.error);
    }
}
