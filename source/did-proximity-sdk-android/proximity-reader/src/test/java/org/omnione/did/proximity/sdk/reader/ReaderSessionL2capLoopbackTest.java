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
import static org.junit.Assert.assertNotNull;
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
import org.omnione.did.proximity.sdk.core.transport.ble.L2capFraming;
import org.omnione.did.proximity.sdk.core.transport.ble.L2capWire;
import org.omnione.did.proximity.sdk.core.util.NoDeadline;
import org.omnione.did.proximity.sdk.holder.HolderSession;
import org.omnione.did.proximity.sdk.holder.ProximityRequest;

import java.io.Closeable;
import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class ReaderSessionL2capLoopbackTest {

    private static final String DOC_TYPE = "eu.europa.ec.eudi.pid.1";
    private static final long WAIT_MS = 5_000L;

    private static final class Channel implements Closeable {
        final AtomicInteger closes = new AtomicInteger();
        private final Closeable[] streams;

        Channel(Closeable... streams) {
            this.streams = streams;
        }

        @Override
        public void close() throws IOException {
            closes.incrementAndGet();
            for (Closeable stream : streams) {
                stream.close();
            }
        }
    }

    private static final class L2capTransport implements Transport {
        private final PipedInputStream in;
        private final PipedOutputStream out;
        final Channel channel;
        private volatile L2capWire wire;

        L2capTransport(PipedInputStream in, PipedOutputStream out) {
            this.in = in;
            this.out = out;
            this.channel = new Channel(in, out);
        }

        @Override
        public void start(@NonNull Listener listener) {
            wire = new L2capWire(in, out, channel, 0L);
            wire.serve(listener);
            listener.onConnected();
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
        final CountDownLatch failed = new CountDownLatch(1);
        volatile ProximityException error;
        volatile HolderSession session;
        volatile byte[] response;

        @Override
        public void onReaderConnected() {
        }

        @Override
        public void onRequest(@NonNull ProximityRequest request) {
            requests.add(request);
            requested.countDown();
            byte[] body = response;
            if (body != null) {
                session.sendResponse(body);
            }
        }

        @Override
        public void onResponseSent() {
        }

        @Override
        public void onError(@NonNull ProximityException e) {
            error = e;
            failed.countDown();
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
        final CountDownLatch failed = new CountDownLatch(1);
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
            failed.countDown();
        }

        @Override
        public void onTerminated() {
            terminated.countDown();
        }
    }

    private HolderSession holder;
    private ReaderSession reader;
    private L2capTransport holderTransport;
    private L2capTransport readerTransport;
    private PipedOutputStream holderOut;
    private final HolderEvents holderEvents = new HolderEvents();
    private final ReaderEvents readerEvents = new ReaderEvents();

    private void engage() throws IOException {
        PipedInputStream holderIn = new PipedInputStream(1 << 16);
        PipedOutputStream readerOut = new PipedOutputStream(holderIn);
        PipedInputStream readerIn = new PipedInputStream(1 << 16);
        holderOut = new PipedOutputStream(readerIn);

        holderTransport = new L2capTransport(holderIn, holderOut);
        holder = HolderSession.create(holderTransport, new NoDeadline(),
                BleMode.PERIPHERAL_SERVER);
        holderEvents.session = holder;
        holder.start(holderEvents);

        readerTransport = new L2capTransport(readerIn, readerOut);
        reader = ReaderSession.create(readerTransport, new NoDeadline());
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

    private static void awaitClosed(Channel channel) throws InterruptedException {
        long deadline = System.currentTimeMillis() + WAIT_MS;
        while (channel.closes.get() == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertTrue("channel not closed", channel.closes.get() > 0);
    }

    @Test
    public void aRequestAndResponseRoundTripOverL2cap() throws Exception {
        holderEvents.response = DeviceResponseFixture.of(DOC_TYPE, "family_name", "Kim");
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
    public void readerTerminationReachesTheHolderAsStatus20AndClosesBothChannels()
            throws Exception {
        holderEvents.response = DeviceResponseFixture.of(DOC_TYPE, "family_name", "Kim");
        engage();
        await(readerEvents.responded);

        reader.terminate();

        await(readerEvents.terminated);
        await(holderEvents.terminated);
        assertNull(holderEvents.error);
        assertNull(readerEvents.error);
        awaitClosed(readerTransport.channel);
        awaitClosed(holderTransport.channel);
    }

    @Test
    public void holderTerminationTravelsAsStatus20() throws Exception {
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
    public void aReaderEofWithoutStatusTerminatesTheHolder() throws Exception {
        engage();
        await(holderEvents.requested);

        readerTransport.stop();

        await(holderEvents.terminated);
        assertNull(holderEvents.error);
    }

    @Test
    public void aResponseLargerThanOneMebibyteIsAnErrorOnTheSender() throws Exception {
        engage();
        await(holderEvents.requested);

        holder.sendResponse(new byte[L2capFraming.MAX_FRAME + 1]);

        await(holderEvents.failed);
        assertNotNull(holderEvents.error);
    }

    @Test
    public void aFrameHeaderLargerThanOneMebibyteIsAnErrorOnTheReceiver() throws Exception {
        engage();
        await(holderEvents.requested);

        holderOut.write(new byte[]{0x00, 0x10, 0x00, 0x01});
        holderOut.flush();

        await(readerEvents.failed);
        assertNotNull(readerEvents.error);
        assertTrue(readerEvents.responses.isEmpty());
    }
}
