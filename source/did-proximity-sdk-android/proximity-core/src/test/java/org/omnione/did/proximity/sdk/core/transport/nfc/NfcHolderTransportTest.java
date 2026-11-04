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

package org.omnione.did.proximity.sdk.core.transport.nfc;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import androidx.annotation.NonNull;

import org.junit.Test;
import org.omnione.did.proximity.sdk.core.ProximityException;
import org.omnione.did.proximity.sdk.core.engagement.NfcOptions;
import org.omnione.did.proximity.sdk.core.nfc.Apdu;
import org.omnione.did.proximity.sdk.core.session.IsoAnnexD;
import org.omnione.did.proximity.sdk.core.session.SessionMessages;
import org.omnione.did.proximity.sdk.core.transport.Transport;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class NfcHolderTransportTest {

    private static final NfcOptions LIMITS = NfcOptions.of(65535, 65536);

    private static final class Recording implements Transport.Listener {
        final List<byte[]> messages = new ArrayList<>();
        final List<String> events = new ArrayList<>();
        Runnable onMessageHook;

        @Override
        public void onConnected() {
            events.add("connected");
        }

        @Override
        public void onMessage(@NonNull byte[] message) {
            messages.add(message);
            events.add("message");
            if (onMessageHook != null) {
                onMessageHook.run();
            }
        }

        @Override
        public void onError(@NonNull ProximityException error) {
            events.add("error:" + error.getMessage());
        }

        @Override
        public void onDisconnected() {
            events.add("disconnected");
        }
    }

    private static final class Sink implements NfcHolderTransport.ResponseSink {
        final List<byte[]> responses = new ArrayList<>();

        @Override
        public void sendResponseApdu(@NonNull byte[] responseApdu) {
            responses.add(responseApdu);
        }
    }

    private static byte[] bytes(int length) {
        byte[] out = new byte[length];
        for (int i = 0; i < length; i++) {
            out[i] = (byte) (i * 3);
        }
        return out;
    }

    private static int status(byte[] response) {
        return Apdu.parseResponse(response).status;
    }

    private static byte[] select(int p2) {
        byte[] apdu = EnvelopeFraming.selectMdocApplication();
        apdu[3] = (byte) p2;
        return apdu;
    }

    private static NfcHolderTransport selectedTransport(Recording listener, Sink sink) {
        NfcHolderTransport transport = new NfcHolderTransport(LIMITS);
        transport.start(listener);
        assertEquals(0x9000, status(transport.processCommandApdu(select(0x0C), sink)));
        return transport;
    }

    private static byte[] lastEnvelope(byte[] message, int le) {
        return EnvelopeFraming.envelope(true, EnvelopeFraming.encodeDo53(message), le, true);
    }

    @Test
    public void selectWithEitherP2ConnectsTheLegAndAnswersSynchronously() {
        for (int p2 : new int[]{0x00, 0x0C}) {
            Recording listener = new Recording();
            Sink sink = new Sink();
            NfcHolderTransport transport = new NfcHolderTransport(LIMITS);
            transport.start(listener);

            byte[] response = transport.processCommandApdu(select(p2), sink);

            assertArrayEquals(IsoAnnexD.hex("9000"), response);
            assertEquals(Arrays.asList("connected"), listener.events);
            assertTrue(transport.isLinkActive());
            assertTrue(sink.responses.isEmpty());
        }
    }

    @Test
    public void aForeignAidOrP2IsRefused() {
        Recording listener = new Recording();
        NfcHolderTransport transport = new NfcHolderTransport(LIMITS);
        transport.start(listener);

        assertEquals(0x6A82, status(transport.processCommandApdu(
                IsoAnnexD.hex("00A4040007D2760000850101"), new Sink())));
        assertEquals(0x6A82, status(transport.processCommandApdu(select(0x04), new Sink())));
        assertTrue(listener.events.isEmpty());
        assertFalse(transport.isLinkActive());
    }

    @Test
    public void anEnvelopeBeforeSelectIsRefused() {
        NfcHolderTransport transport = new NfcHolderTransport(LIMITS);
        transport.start(new Recording());

        assertEquals(0x6985, status(transport.processCommandApdu(
                lastEnvelope(new byte[]{1}, 256), new Sink())));
    }

    @Test
    public void aChainedEnvelopeIsReassembledAndDeliveredOnce() {
        Recording listener = new Recording();
        Sink sink = new Sink();
        NfcHolderTransport transport = selectedTransport(listener, sink);
        byte[] message = bytes(600);
        byte[] frame = EnvelopeFraming.encodeDo53(message);

        assertEquals(0x9000, status(transport.processCommandApdu(
                EnvelopeFraming.envelope(false, Arrays.copyOfRange(frame, 0, 250), 256, false),
                sink)));
        assertEquals(0x9000, status(transport.processCommandApdu(
                EnvelopeFraming.envelope(false, Arrays.copyOfRange(frame, 250, 500), 256, false),
                sink)));
        assertTrue(listener.messages.isEmpty());
        byte[] response = transport.processCommandApdu(
                EnvelopeFraming.envelope(true, Arrays.copyOfRange(frame, 500, frame.length),
                        256, false), sink);

        assertNull("no response yet: the holder has to answer later", response);
        assertEquals(1, listener.messages.size());
        assertArrayEquals(message, listener.messages.get(0));
    }

    @Test
    public void aSynchronousSendInsideOnMessageBecomesTheEnvelopeResponse() {
        Recording listener = new Recording();
        Sink sink = new Sink();
        NfcHolderTransport transport = selectedTransport(listener, sink);
        byte[] answer = bytes(40);
        listener.onMessageHook = () -> transport.send(answer);

        byte[] response = transport.processCommandApdu(lastEnvelope(new byte[]{7}, 256), sink);

        assertNotNull(response);
        Apdu.Response parsed = Apdu.parseResponse(response);
        assertEquals(0x9000, parsed.status);
        assertArrayEquals(answer, EnvelopeFraming.decodeDo53(parsed.data));
        assertTrue(sink.responses.isEmpty());
    }

    @Test
    public void aDeferredSendGoesThroughTheSinkAndTheRestThroughGetResponse() {
        Recording listener = new Recording();
        Sink sink = new Sink();
        NfcHolderTransport transport = selectedTransport(listener, sink);
        byte[] answer = bytes(700);

        assertNull(transport.processCommandApdu(lastEnvelope(new byte[]{7}, 256), sink));
        transport.send(answer);

        assertEquals(1, sink.responses.size());
        Apdu.Response first = Apdu.parseResponse(sink.responses.get(0));
        assertEquals(256, first.data.length);
        assertEquals(0x6100, first.status);

        Apdu.Response second = Apdu.parseResponse(
                transport.processCommandApdu(EnvelopeFraming.getResponse(256, false), sink));
        assertEquals(0x61C0, second.status);
        Apdu.Response third = Apdu.parseResponse(
                transport.processCommandApdu(EnvelopeFraming.getResponse(192, false), sink));
        assertEquals(0x9000, third.status);

        byte[] joined = new byte[first.data.length + second.data.length + third.data.length];
        System.arraycopy(first.data, 0, joined, 0, first.data.length);
        System.arraycopy(second.data, 0, joined, first.data.length, second.data.length);
        System.arraycopy(third.data, 0, joined, first.data.length + second.data.length,
                third.data.length);
        assertArrayEquals(answer, EnvelopeFraming.decodeDo53(joined));

        assertEquals(0x6985, status(transport.processCommandApdu(
                EnvelopeFraming.getResponse(256, false), sink)));
    }

    @Test
    public void theLeOfTheLastEnvelopeBoundsEveryChunk() {
        Recording listener = new Recording();
        Sink sink = new Sink();
        NfcHolderTransport transport = selectedTransport(listener, sink);

        assertNull(transport.processCommandApdu(lastEnvelope(new byte[]{7}, 100), sink));
        transport.send(bytes(150));

        assertEquals(100, Apdu.parseResponse(sink.responses.get(0)).data.length);
        assertEquals(0x6135, Apdu.parseResponse(sink.responses.get(0)).status);
    }

    @Test
    public void aSendFromAnotherThreadWhileOnMessageIsStillRunningIsReturnedInline()
            throws InterruptedException {
        Recording listener = new Recording();
        Sink sink = new Sink();
        NfcHolderTransport transport = selectedTransport(listener, sink);
        byte[] answer = bytes(10);
        CountDownLatch sent = new CountDownLatch(1);
        listener.onMessageHook = () -> {
            Thread worker = new Thread(() -> {
                transport.send(answer);
                sent.countDown();
            });
            worker.start();
            try {
                assertTrue(sent.await(5, TimeUnit.SECONDS));
            } catch (InterruptedException e) {
                throw new AssertionError(e);
            }
        };

        byte[] response = transport.processCommandApdu(lastEnvelope(new byte[]{7}, 256), sink);

        assertNotNull(response);
        assertArrayEquals(answer, EnvelopeFraming.decodeDo53(Apdu.parseResponse(response).data));
        assertTrue(sink.responses.isEmpty());
    }

    @Test
    public void aTerminationIsDeliveredAndAnsweredSynchronously() {
        Recording listener = new Recording();
        Sink sink = new Sink();
        NfcHolderTransport transport = selectedTransport(listener, sink);
        listener.onMessageHook = transport::stop;

        byte[] response = transport.processCommandApdu(
                lastEnvelope(SessionMessages.termination(), 256), sink);

        assertArrayEquals(IsoAnnexD.hex("9000"), response);
        assertEquals(1, listener.messages.size());
        assertFalse(transport.isLinkActive());
        assertEquals(0x6A82, status(transport.processCommandApdu(select(0x0C), sink)));
    }

    @Test
    public void stopWhileAnEnvelopeIsPendingAnswersWithABare9000() {
        Recording listener = new Recording();
        Sink sink = new Sink();
        NfcHolderTransport transport = selectedTransport(listener, sink);
        assertNull(transport.processCommandApdu(lastEnvelope(new byte[]{7}, 256), sink));

        transport.stop();
        transport.stop();

        assertEquals(1, sink.responses.size());
        assertArrayEquals(IsoAnnexD.hex("9000"), sink.responses.get(0));
        assertThrows(ProximityException.class, () -> transport.send(new byte[]{1}));
    }

    @Test
    public void sendWithoutAPendingEnvelopeIsRefused() {
        Recording listener = new Recording();
        NfcHolderTransport transport = selectedTransport(listener, new Sink());

        ProximityException error = assertThrows(ProximityException.class,
                () -> transport.send(new byte[]{1}));

        assertTrue(error.getMessage().contains("cannot push over NFC"));
    }

    @Test
    public void deactivationOfALiveLinkDisconnects() {
        Recording listener = new Recording();
        Sink sink = new Sink();
        NfcHolderTransport transport = selectedTransport(listener, sink);
        assertNull(transport.processCommandApdu(lastEnvelope(new byte[]{7}, 256), sink));

        transport.onDeactivated();
        transport.onDeactivated();

        assertEquals(Arrays.asList("connected", "message", "disconnected"), listener.events);
        assertFalse(transport.isLinkActive());
        assertThrows(ProximityException.class, () -> transport.send(new byte[]{1}));
    }

    @Test
    public void deactivationAfterStopIsSilent() {
        Recording listener = new Recording();
        NfcHolderTransport transport = selectedTransport(listener, new Sink());
        transport.stop();

        transport.onDeactivated();

        assertEquals(Arrays.asList("connected"), listener.events);
    }

    @Test
    public void malformedFramesAreRejectedWithoutDelivery() {
        Recording listener = new Recording();
        Sink sink = new Sink();
        NfcHolderTransport transport = selectedTransport(listener, sink);

        assertEquals(0x6A80, status(transport.processCommandApdu(
                EnvelopeFraming.envelope(true, IsoAnnexD.hex("538000"), 256, false), sink)));
        assertEquals(0x6E00, status(transport.processCommandApdu(
                IsoAnnexD.hex("80C3000001AA"), sink)));
        assertEquals(0x6D00, status(transport.processCommandApdu(
                IsoAnnexD.hex("00B0000001"), sink)));
        assertTrue(listener.messages.isEmpty());
    }
}
