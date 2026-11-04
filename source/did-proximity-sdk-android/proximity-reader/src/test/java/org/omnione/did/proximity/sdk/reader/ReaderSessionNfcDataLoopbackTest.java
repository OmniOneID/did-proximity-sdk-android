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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import androidx.annotation.NonNull;

import com.upokecenter.cbor.CBORObject;

import org.junit.Test;
import org.omnione.did.proximity.sdk.core.ProximityException;
import org.omnione.did.proximity.sdk.core.cbor.CborTags;
import org.omnione.did.proximity.sdk.core.engagement.BleMode;
import org.omnione.did.proximity.sdk.core.engagement.NfcOptions;
import org.omnione.did.proximity.sdk.core.engagement.TransportMode;
import org.omnione.did.proximity.sdk.core.engagement.TransportPreference;
import org.omnione.did.proximity.sdk.core.engagement.UnsupportedTransportException;
import org.omnione.did.proximity.sdk.core.message.ItemsRequest;
import org.omnione.did.proximity.sdk.core.nfc.Apdu;
import org.omnione.did.proximity.sdk.core.nfc.ApduChannel;
import org.omnione.did.proximity.sdk.core.nfc.BleCarrier;
import org.omnione.did.proximity.sdk.core.nfc.HandoverRequester;
import org.omnione.did.proximity.sdk.core.nfc.HandoverResponder;
import org.omnione.did.proximity.sdk.core.nfc.NfcCarrier;
import org.omnione.did.proximity.sdk.core.nfc.NfcHandoverResult;
import org.omnione.did.proximity.sdk.core.transport.Transport;
import org.omnione.did.proximity.sdk.core.transport.nfc.NfcDataTransfer;
import org.omnione.did.proximity.sdk.core.transport.nfc.NfcHolderTransport;
import org.omnione.did.proximity.sdk.core.util.NoDeadline;
import org.omnione.did.proximity.sdk.holder.EngagementMethod;
import org.omnione.did.proximity.sdk.holder.HolderSession;
import org.omnione.did.proximity.sdk.holder.NfcEngagementRegistry;
import org.omnione.did.proximity.sdk.holder.ProximityRequest;
import org.omnione.did.proximity.sdk.holder.nfc.HceRouter;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;

public class ReaderSessionNfcDataLoopbackTest {

    private static final String DOC_TYPE = "eu.europa.ec.eudi.pid.1";
    private static final NfcOptions HOLDER_LIMITS = NfcOptions.of(0xFFFF, 0x10000);
    private static final TransportPreference NFC_FIRST =
            TransportPreference.of(TransportMode.NFC, TransportMode.BLE_PERIPHERAL_SERVER);

    private static final class Tag implements ApduChannel {
        private final HceRouter router;
        private final LinkedBlockingQueue<byte[]> deferred = new LinkedBlockingQueue<>();
        final List<byte[]> commands = new CopyOnWriteArrayList<>();
        private volatile boolean open = true;
        private int maxTransceiveLength = 261;
        private UnaryOperator<byte[]> tamper = UnaryOperator.identity();

        Tag(@NonNull HceRouter router) {
            this.router = router;
        }

        Tag maxTransceiveLength(int value) {
            this.maxTransceiveLength = value;
            return this;
        }

        Tag tampering(@NonNull UnaryOperator<byte[]> tamper) {
            this.tamper = tamper;
            return this;
        }

        void drop() {
            open = false;
            deferred.add(new byte[0]);
        }

        @NonNull
        @Override
        public synchronized byte[] transceive(@NonNull byte[] commandApdu) {
            if (!open) {
                throw new ProximityException("tag lost");
            }
            commands.add(commandApdu);
            byte[] response = router.processCommandApdu(commandApdu, deferred::add);
            if (response == null) {
                try {
                    response = deferred.poll(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new ProximityException("interrupted", e);
                }
            }
            if (!open || response == null || response.length == 0) {
                throw new ProximityException("tag lost");
            }
            return tamper.apply(response);
        }

        @Override
        public int maxTransceiveLength() {
            return maxTransceiveLength;
        }

        @Override
        public boolean isLinkOpen() {
            return open;
        }

        @Override
        public void close() {
            open = false;
            deferred.add(new byte[0]);
        }
    }

    private static final class HolderSide implements HolderSession.Listener {
        final List<ProximityRequest> requests = new CopyOnWriteArrayList<>();
        final List<String> events = new CopyOnWriteArrayList<>();
        final CountDownLatch terminated = new CountDownLatch(1);
        final CountDownLatch requested = new CountDownLatch(1);
        HolderSession session;
        String answer = "Kim";
        boolean answerSynchronously = true;

        @Override public void onReaderConnected() { events.add("connected"); }

        @Override public void onRequest(@NonNull ProximityRequest request) {
            requests.add(request);
            events.add("request");
            requested.countDown();
            if (answerSynchronously) {
                session.sendResponse(DeviceResponseFixture.of(DOC_TYPE, "family_name", answer));
            }
        }

        @Override public void onResponseSent() { events.add("sent"); }
        @Override public void onError(@NonNull ProximityException error) {
            events.add("error:" + error.getMessage());
            terminated.countDown();
        }
        @Override public void onTerminated() {
            events.add("terminated");
            terminated.countDown();
        }
        @Override public void onEngagementDelivered(@NonNull EngagementMethod method) {
            events.add("engaged:" + method);
        }
    }

    private static final class ReaderSide implements ReaderSession.Listener {
        final List<ProximityResponse> responses = new CopyOnWriteArrayList<>();
        final List<String> events = new CopyOnWriteArrayList<>();
        final CountDownLatch ended = new CountDownLatch(1);
        final CountDownLatch responded = new CountDownLatch(1);

        @Override public void onMdocConnected() { events.add("connected"); }
        @Override public void onResponse(@NonNull ProximityResponse response) {
            responses.add(response);
            events.add("response");
            responded.countDown();
        }
        @Override public void onError(@NonNull ProximityException error) {
            events.add("error:" + error.getMessage());
            ended.countDown();
        }
        @Override public void onTerminated() {
            events.add("terminated");
            ended.countDown();
        }
    }

    private static final class Bench {
        final NfcHolderTransport nfcTransport = new NfcHolderTransport(HOLDER_LIMITS);
        final HolderSide holderSide = new HolderSide();
        final HolderSession holder;
        final HandoverResponder responder;
        final HceRouter router;
        Tag tag;

        Bench() {
            holder = HolderSession.create(nfcTransport, new NoDeadline(), BleMode.PERIPHERAL_SERVER);
            holderSide.session = holder;
            Map<BleMode, UUID> uuids = NfcEngagementRegistry.serviceUuids(holder);
            responder = HandoverResponder.staticHandover(
                    NfcEngagementRegistry.nfcEngagementBytes(holder),
                    BleCarrier.of(EnumSet.of(BleMode.PERIPHERAL_SERVER),
                            uuids.get(BleMode.PERIPHERAL_SERVER)),
                    NfcCarrier.of(HOLDER_LIMITS),
                    (hs, hr) -> NfcEngagementRegistry.latch(holder, hs, hr));
            router = new HceRouter(() -> responder, () -> nfcTransport);
            tag = new Tag(router);
        }

        NfcHandoverResult tap(TransportPreference preference) {
            holder.start(holderSide);
            return HandoverRequester.create(UUID.randomUUID(), preference).engage(tag);
        }
    }

    private static ItemsRequest request() {
        return request(1);
    }

    private static ItemsRequest request(int padding) {
        Map<String, Boolean> elements = new LinkedHashMap<>();
        elements.put("family_name", false);
        for (int i = 1; i < padding; i++) {
            elements.put("padding_element_identifier_" + i, false);
        }
        Map<String, Map<String, Boolean>> nameSpaces = new LinkedHashMap<>();
        nameSpaces.put(DOC_TYPE, elements);
        return ItemsRequest.of(DOC_TYPE, nameSpaces);
    }

    private static void awaitLinkClosed(Tag tag) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 2_000L;
        while (tag.isLinkOpen() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertFalse("the reader's close thread must close the tag channel", tag.isLinkOpen());
    }

    private static long count(List<byte[]> commands, int ins) {
        return commands.stream().filter(c -> (c[1] & 0xFF) == ins).count();
    }

    private static ReaderSession startReader(NfcHandoverResult handover, ReaderSide side) {
        return startReader(handover, side, request());
    }

    private static ReaderSession startReader(NfcHandoverResult handover, ReaderSide side,
                                             ItemsRequest request) {
        ReaderSession reader = ReaderSession.create(NFC_FIRST, new NoDeadline());
        reader.start(handover, request, side);
        return reader;
    }

    @Test
    public void theTapHandsTheOpenChannelToTheReaderWhichSelectsNfc() {
        Bench bench = new Bench();

        NfcHandoverResult handover = bench.tap(NFC_FIRST);

        assertEquals(TransportMode.NFC, handover.selected());
        assertEquals(HOLDER_LIMITS, handover.nfcOptions());
        assertSame(bench.tag, handover.channel());
        assertNull(handover.mode());
    }

    @Test
    public void aBleFirstReaderIgnoresTheNfcCarrierOfTheSameSelect() {
        Bench bench = new Bench();

        NfcHandoverResult handover = bench.tap(
                TransportPreference.of(TransportMode.BLE_PERIPHERAL_SERVER, TransportMode.NFC));

        assertEquals(TransportMode.BLE_PERIPHERAL_SERVER, handover.selected());
        assertEquals(BleMode.PERIPHERAL_SERVER, handover.mode());
        assertNull(handover.channel());
    }

    @Test
    public void aSessionRoundTripsOverNfcWithChainingBothWays() throws InterruptedException {
        Bench bench = new Bench();
        bench.tag.maxTransceiveLength(261);
        bench.holderSide.answer = "K".repeat(900);
        NfcHandoverResult handover = bench.tap(NFC_FIRST);
        ReaderSide readerSide = new ReaderSide();

        ReaderSession reader = startReader(handover, readerSide, request(30));

        assertTrue(readerSide.responded.await(10, TimeUnit.SECONDS));
        assertTrue(readerSide.ended.await(10, TimeUnit.SECONDS));
        assertTrue(bench.holderSide.terminated.await(10, TimeUnit.SECONDS));
        assertEquals(30, bench.holderSide.requests.get(0).requestedElements.get(DOC_TYPE).size());
        assertEquals("K".repeat(900),
                readerSide.responses.get(0).documents.get(0).flatClaims().get("family_name"));
        assertArrayEquals(
                CborTags.untag(CBORObject.DecodeFromBytes(
                        bench.holderSide.requests.get(0).sessionTranscript)),
                readerSide.responses.get(0).sessionTranscript);
        assertEquals(List.of("connected", "response", "terminated"), readerSide.events);
        assertTrue(bench.holderSide.events.toString(),
                bench.holderSide.events.contains("terminated"));
        assertTrue(bench.holderSide.events.contains("engaged:NFC"));
        assertTrue(count(bench.tag.commands, NfcDataTransfer.INS_ENVELOPE) >= 5);
        assertTrue(count(bench.tag.commands, NfcDataTransfer.INS_GET_RESPONSE) >= 3);
        for (byte[] command : bench.tag.commands) {
            assertTrue(command.length <= 261);
        }
        reader.closeWithoutNotifying();
        awaitLinkClosed(bench.tag);
    }

    @Test
    public void aMultipazStyleRemainderMiscountStillCompletes() throws InterruptedException {
        Bench bench = new Bench();
        bench.tag.maxTransceiveLength(261).tampering(response -> {
            Apdu.Response parsed = Apdu.parseResponse(response);
            if ((parsed.status & 0xFF00) == 0x6100 && (parsed.status & 0xFF) != 0) {
                return Apdu.response(parsed.data, 0x6100 | ((parsed.status + 33) & 0xFF));
            }
            return response;
        });
        bench.holderSide.answer = "M".repeat(700);
        NfcHandoverResult handover = bench.tap(NFC_FIRST);
        ReaderSide readerSide = new ReaderSide();

        startReader(handover, readerSide);

        assertTrue(readerSide.responded.await(10, TimeUnit.SECONDS));
        assertEquals("M".repeat(700),
                readerSide.responses.get(0).documents.get(0).flatClaims().get("family_name"));
    }

    @Test
    public void aDeferredConsentAnswersThePendingEnvelopeFromAnotherThread()
            throws InterruptedException {
        Bench bench = new Bench();
        bench.holderSide.answerSynchronously = false;
        NfcHandoverResult handover = bench.tap(NFC_FIRST);
        ReaderSide readerSide = new ReaderSide();

        startReader(handover, readerSide);
        assertTrue(bench.holderSide.requested.await(10, TimeUnit.SECONDS));
        Thread.sleep(100);
        assertTrue(readerSide.responses.isEmpty());
        assertTrue(NfcEngagementRegistry.nfcTransport(bench.holder) == null);
        assertTrue(bench.nfcTransport.isLinkActive());
        new Thread(() -> bench.holder.sendResponse(
                DeviceResponseFixture.of(DOC_TYPE, "family_name", "Lee"))).start();

        assertTrue(readerSide.responded.await(10, TimeUnit.SECONDS));
        assertEquals("Lee",
                readerSide.responses.get(0).documents.get(0).flatClaims().get("family_name"));
        assertTrue(readerSide.ended.await(10, TimeUnit.SECONDS));
    }

    @Test
    public void theHolderTerminatingAnswersThePendingEnvelopeWithStatus20()
            throws InterruptedException {
        Bench bench = new Bench();
        bench.holderSide.answerSynchronously = false;
        NfcHandoverResult handover = bench.tap(NFC_FIRST);
        ReaderSide readerSide = new ReaderSide();

        startReader(handover, readerSide);
        assertTrue(bench.holderSide.requested.await(10, TimeUnit.SECONDS));
        Thread.sleep(100);
        bench.holder.terminate();

        assertTrue(readerSide.ended.await(10, TimeUnit.SECONDS));
        assertEquals(List.of("connected", "terminated"), readerSide.events);
        assertTrue(bench.holderSide.events.contains("terminated"));
    }

    @Test
    public void theHolderClosingAnswersThePendingEnvelopeWithABare9000()
            throws InterruptedException {
        Bench bench = new Bench();
        bench.holderSide.answerSynchronously = false;
        NfcHandoverResult handover = bench.tap(NFC_FIRST);
        ReaderSide readerSide = new ReaderSide();

        startReader(handover, readerSide);
        assertTrue(bench.holderSide.requested.await(10, TimeUnit.SECONDS));
        Thread.sleep(100);
        bench.holder.closeWithoutNotifying();

        assertTrue(readerSide.ended.await(10, TimeUnit.SECONDS));
        assertEquals(List.of("connected", "terminated"), readerSide.events);
    }

    @Test
    public void aLostTagFailsTheReaderAndDeactivationEndsTheHolder() throws InterruptedException {
        Bench bench = new Bench();
        bench.holderSide.answerSynchronously = false;
        NfcHandoverResult handover = bench.tap(NFC_FIRST);
        ReaderSide readerSide = new ReaderSide();

        startReader(handover, readerSide);
        assertTrue(bench.holderSide.requested.await(10, TimeUnit.SECONDS));
        Thread.sleep(100);
        bench.tag.drop();
        bench.router.onDeactivated();

        assertTrue(readerSide.ended.await(10, TimeUnit.SECONDS));
        assertEquals("error:NFC link lost during data retrieval", readerSide.events.get(1));
        assertTrue(bench.holderSide.terminated.await(10, TimeUnit.SECONDS));
        assertTrue(bench.holderSide.events.contains("terminated"));
        assertFalse(bench.nfcTransport.isLinkActive());
    }

    @Test
    public void closingTheReaderClosesTheTagChannel() throws InterruptedException {
        Bench bench = new Bench();
        bench.holderSide.answerSynchronously = false;
        NfcHandoverResult handover = bench.tap(NFC_FIRST);
        ReaderSide readerSide = new ReaderSide();
        ReaderSession reader = startReader(handover, readerSide);
        assertTrue(bench.holderSide.requested.await(10, TimeUnit.SECONDS));

        reader.closeWithoutNotifying();

        awaitLinkClosed(bench.tag);
        Thread.sleep(200);
        assertEquals(List.of("connected"), readerSide.events);
    }

    @Test
    public void theQrPathDropsNfcFromThePreferenceAndRefusesAnNfcOnlyOne() {
        assertEquals(List.of(TransportMode.BLE_PERIPHERAL_SERVER),
                ReaderSession.withoutNfc(NFC_FIRST).order());
        TransportPreference bleOnly = TransportPreference.of(TransportMode.BLE_CENTRAL_CLIENT);
        assertSame(bleOnly, ReaderSession.withoutNfc(bleOnly));
        assertThrows(UnsupportedTransportException.class,
                () -> ReaderSession.withoutNfc(TransportPreference.of(TransportMode.NFC)));

        ReaderSide side = new ReaderSide();
        ReaderSession reader = ReaderSession.create(
                TransportPreference.of(TransportMode.NFC), new NoDeadline());
        reader.start("mdoc:owBjMS4wAQ", request(), side);

        assertEquals(1, side.events.size());
        assertTrue(side.events.get(0).startsWith("error:NFC data retrieval needs an NFC tap"));
    }

    @Test
    public void aBleHandoverStillTakesTheBlePath() {
        Bench bench = new Bench();
        NfcHandoverResult handover = bench.tap(
                TransportPreference.of(TransportMode.BLE_PERIPHERAL_SERVER, TransportMode.NFC));
        Transport[] injected = new Transport[1];
        ReaderSide side = new ReaderSide();
        Transport wire = new Transport() {
            @Override public void start(@NonNull Listener listener) { injected[0] = this; }
            @Override public void send(@NonNull byte[] message) { }
            @Override public void stop() { }
        };

        ReaderSession reader = ReaderSession.create(wire, new NoDeadline(), NFC_FIRST);
        reader.start(handover, request(), side);

        assertNotNull(injected[0]);
        assertTrue(side.events.isEmpty());
        reader.closeWithoutNotifying();
    }
}
