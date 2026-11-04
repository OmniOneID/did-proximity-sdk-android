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
import org.omnione.did.proximity.sdk.core.nfc.ApduChannel;
import org.omnione.did.proximity.sdk.core.session.IsoAnnexD;
import org.omnione.did.proximity.sdk.core.session.SessionMessages;
import org.omnione.did.proximity.sdk.core.transport.TerminationFrames;
import org.omnione.did.proximity.sdk.core.transport.Transport;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class NfcReaderTransportTest {

    private static final NfcOptions HOLDER = NfcOptions.of(65535, 65536);

    private static final class Recording implements Transport.Listener {
        final LinkedBlockingQueue<byte[]> messages = new LinkedBlockingQueue<>();
        final List<String> events = new CopyOnWriteArrayList<>();
        final CountDownLatch connected = new CountDownLatch(1);
        final CountDownLatch ended = new CountDownLatch(1);

        @Override
        public void onConnected() {
            events.add("connected");
            connected.countDown();
        }

        @Override
        public void onMessage(@NonNull byte[] message) {
            events.add("message");
            messages.add(message);
        }

        @Override
        public void onError(@NonNull ProximityException error) {
            events.add("error:" + error.getMessage());
            ended.countDown();
        }

        @Override
        public void onDisconnected() {
            events.add("disconnected");
            ended.countDown();
        }
    }

    private static final class Echo implements Transport.Listener {
        final NfcHolderTransport holder;
        final List<byte[]> received = new CopyOnWriteArrayList<>();
        final List<String> events = new CopyOnWriteArrayList<>();
        int replyLength = 20;
        boolean replySynchronously = true;

        Echo(NfcHolderTransport holder) {
            this.holder = holder;
        }

        @Override
        public void onConnected() {
            events.add("connected");
        }

        @Override
        public void onMessage(@NonNull byte[] message) {
            received.add(message);
            if (TerminationFrames.isTermination(message)) {
                events.add("terminated");
                return;
            }
            if (replySynchronously) {
                holder.send(bytes(replyLength));
            }
        }

        @Override
        public void onError(@NonNull ProximityException error) {
            events.add("error");
        }

        @Override
        public void onDisconnected() {
            events.add("disconnected");
        }
    }

    private static byte[] bytes(int length) {
        byte[] out = new byte[length];
        for (int i = 0; i < length; i++) {
            out[i] = (byte) (i * 5 + 1);
        }
        return out;
    }

    private static NfcHolderTransport holder(Echo[] out) {
        NfcHolderTransport holder = new NfcHolderTransport(HOLDER);
        Echo echo = new Echo(holder);
        holder.start(echo);
        out[0] = echo;
        return holder;
    }

    private static byte[] await(LinkedBlockingQueue<byte[]> queue) throws InterruptedException {
        byte[] message = queue.poll(5, TimeUnit.SECONDS);
        assertNotNull("no message within the wait", message);
        return message;
    }

    @Test
    public void sizesFollowTheHolderLimitsAndTheTransceiveLength() {
        HolderBackedChannel shortForm = new HolderBackedChannel(new NfcHolderTransport(HOLDER))
                .maxTransceiveLength(261);
        NfcReaderTransport reader = new NfcReaderTransport(shortForm, HOLDER);
        assertFalse(reader.extendedLength());
        assertEquals(251, reader.chunkLength());
        assertEquals(256, reader.le());
        reader.stop();

        HolderBackedChannel extended = new HolderBackedChannel(new NfcHolderTransport(HOLDER))
                .maxTransceiveLength(65535).extendedLength(true);
        reader = new NfcReaderTransport(extended, HOLDER);
        assertTrue(reader.extendedLength());
        assertEquals(65525, reader.chunkLength());
        assertEquals(65533, reader.le());
        reader.stop();

        HolderBackedChannel tiny = new HolderBackedChannel(new NfcHolderTransport(HOLDER))
                .maxTransceiveLength(65535).extendedLength(true);
        reader = new NfcReaderTransport(tiny, NfcOptions.of(300, 400));
        assertEquals(300, reader.chunkLength());
        assertEquals(400, reader.le());
        reader.stop();
    }

    @Test
    public void startSelectsTheApplicationAndConnects() throws InterruptedException {
        Echo[] echo = new Echo[1];
        HolderBackedChannel channel = new HolderBackedChannel(holder(echo));
        NfcReaderTransport reader = new NfcReaderTransport(channel, HOLDER);
        Recording listener = new Recording();

        reader.start(listener);

        assertTrue(listener.connected.await(5, TimeUnit.SECONDS));
        assertArrayEquals(IsoAnnexD.hex("00A4040C07A0000002480400"), channel.commands().get(0));
        assertEquals(List.of("connected"), echo[0].events);
        reader.stop();
    }

    @Test
    public void aRefusedSelectIsReportedAsAnError() throws InterruptedException {
        ApduChannel refusing = new ApduChannel() {
            @NonNull
            @Override
            public byte[] transceive(@NonNull byte[] commandApdu) {
                return IsoAnnexD.hex("6A82");
            }

            @Override
            public int maxTransceiveLength() {
                return 261;
            }
        };
        NfcReaderTransport reader = new NfcReaderTransport(refusing, HOLDER);
        Recording listener = new Recording();

        reader.start(listener);

        assertTrue(listener.ended.await(5, TimeUnit.SECONDS));
        assertTrue(listener.events.get(0).startsWith("error:SELECT"));
        reader.stop();
    }

    @Test
    public void aShortFormExchangeChainsBothWays() throws InterruptedException {
        Echo[] echo = new Echo[1];
        HolderBackedChannel channel = new HolderBackedChannel(holder(echo)).maxTransceiveLength(261);
        echo[0].replyLength = 900;
        NfcReaderTransport reader = new NfcReaderTransport(channel, HOLDER);
        Recording listener = new Recording();
        reader.start(listener);
        assertTrue(listener.connected.await(5, TimeUnit.SECONDS));
        byte[] request = bytes(600);

        reader.send(request);
        byte[] reply = await(listener.messages);

        assertArrayEquals(bytes(900), reply);
        assertArrayEquals(request, echo[0].received.get(0));
        long envelopes = channel.commands().stream()
                .filter(c -> (c[1] & 0xFF) == NfcDataTransfer.INS_ENVELOPE).count();
        long getResponses = channel.commands().stream()
                .filter(c -> (c[1] & 0xFF) == NfcDataTransfer.INS_GET_RESPONSE).count();
        assertEquals(3, envelopes);
        assertEquals(3, getResponses);
        for (byte[] command : channel.commands()) {
            assertTrue(command.length <= 261);
        }
        reader.stop();
    }

    @Test
    public void anExtendedLengthExchangeUsesOneEnvelope() throws InterruptedException {
        Echo[] echo = new Echo[1];
        HolderBackedChannel channel = new HolderBackedChannel(holder(echo))
                .maxTransceiveLength(65535).extendedLength(true);
        echo[0].replyLength = 5000;
        NfcReaderTransport reader = new NfcReaderTransport(channel, HOLDER);
        Recording listener = new Recording();
        reader.start(listener);
        assertTrue(listener.connected.await(5, TimeUnit.SECONDS));

        reader.send(bytes(3000));
        byte[] reply = await(listener.messages);

        assertArrayEquals(bytes(5000), reply);
        assertEquals(2, channel.commands().size());
        assertEquals(0x00, channel.commands().get(1)[0]);
        reader.stop();
    }

    @Test
    public void every61FamilyCaseIsWalkedByGetResponse() throws InterruptedException {
        for (int replyLength : new int[]{200, 300, 1000}) {
            Echo[] echo = new Echo[1];
            HolderBackedChannel channel =
                    new HolderBackedChannel(holder(echo)).maxTransceiveLength(261);
            echo[0].replyLength = replyLength;
            NfcReaderTransport reader = new NfcReaderTransport(channel, HOLDER);
            Recording listener = new Recording();
            reader.start(listener);
            assertTrue(listener.connected.await(5, TimeUnit.SECONDS));

            reader.send(new byte[]{1});

            assertArrayEquals(bytes(replyLength), await(listener.messages));
            reader.stop();
        }
    }

    @Test
    public void aMultipazStyleRemainderMiscountStillCompletes() throws InterruptedException {
        Echo[] echo = new Echo[1];
        HolderBackedChannel channel = new HolderBackedChannel(holder(echo))
                .maxTransceiveLength(261)
                .tamperingResponses(response -> {
                    Apdu.Response parsed = Apdu.parseResponse(response);
                    if ((parsed.status & 0xFF00) == 0x6100 && (parsed.status & 0xFF) != 0) {
                        return Apdu.response(parsed.data, 0x6100 | ((parsed.status + 40) & 0xFF));
                    }
                    return response;
                });
        echo[0].replyLength = 700;
        NfcReaderTransport reader = new NfcReaderTransport(channel, HOLDER);
        Recording listener = new Recording();
        reader.start(listener);
        assertTrue(listener.connected.await(5, TimeUnit.SECONDS));

        reader.send(new byte[]{1});

        assertArrayEquals(bytes(700), await(listener.messages));
        reader.stop();
    }

    @Test
    public void aDeferredHolderAnswerArrivesThroughTheSink() throws InterruptedException {
        Echo[] echo = new Echo[1];
        NfcHolderTransport holder = holder(echo);
        HolderBackedChannel channel = new HolderBackedChannel(holder);
        echo[0].replySynchronously = false;
        NfcReaderTransport reader = new NfcReaderTransport(channel, HOLDER);
        Recording listener = new Recording();
        reader.start(listener);
        assertTrue(listener.connected.await(5, TimeUnit.SECONDS));

        reader.send(new byte[]{1});
        Thread.sleep(100);
        assertTrue(listener.messages.isEmpty());
        new Thread(() -> holder.send(bytes(30))).start();

        assertArrayEquals(bytes(30), await(listener.messages));
        reader.stop();
    }

    @Test
    public void aTerminationGetsNoCallbackAndABare9000FromTheHolder() throws InterruptedException {
        Echo[] echo = new Echo[1];
        HolderBackedChannel channel = new HolderBackedChannel(holder(echo));
        NfcReaderTransport reader = new NfcReaderTransport(channel, HOLDER);
        Recording listener = new Recording();
        reader.start(listener);
        assertTrue(listener.connected.await(5, TimeUnit.SECONDS));

        reader.send(SessionMessages.termination());
        Thread.sleep(200);

        assertEquals(List.of("connected"), listener.events);
        assertEquals(List.of("connected", "terminated"), echo[0].events);
        reader.stop();
    }

    @Test
    public void anEmptyAnswerToADataMessageMeansTheHolderWentAway() throws InterruptedException {
        Echo[] echo = new Echo[1];
        NfcHolderTransport holder = holder(echo);
        HolderBackedChannel channel = new HolderBackedChannel(holder);
        echo[0].replySynchronously = false;
        NfcReaderTransport reader = new NfcReaderTransport(channel, HOLDER);
        Recording listener = new Recording();
        reader.start(listener);
        assertTrue(listener.connected.await(5, TimeUnit.SECONDS));

        reader.send(new byte[]{1});
        Thread.sleep(100);
        holder.stop();

        assertTrue(listener.ended.await(5, TimeUnit.SECONDS));
        assertEquals(List.of("connected", "disconnected"), listener.events);
        reader.stop();
    }

    @Test
    public void aLostLinkIsReportedUnlessTheTransportWasStopped() throws InterruptedException {
        Echo[] echo = new Echo[1];
        NfcHolderTransport holder = holder(echo);
        HolderBackedChannel channel = new HolderBackedChannel(holder);
        echo[0].replySynchronously = false;
        NfcReaderTransport reader = new NfcReaderTransport(channel, HOLDER);
        Recording listener = new Recording();
        reader.start(listener);
        assertTrue(listener.connected.await(5, TimeUnit.SECONDS));

        reader.send(new byte[]{1});
        Thread.sleep(100);
        channel.dropLink();

        assertTrue(listener.ended.await(5, TimeUnit.SECONDS));
        assertEquals("error:NFC link lost during data retrieval", listener.events.get(1));
        reader.stop();
    }

    @Test
    public void stopClosesTheChannelAndAbortsABlockedExchange() throws InterruptedException {
        Echo[] echo = new Echo[1];
        NfcHolderTransport holder = holder(echo);
        HolderBackedChannel channel = new HolderBackedChannel(holder);
        echo[0].replySynchronously = false;
        NfcReaderTransport reader = new NfcReaderTransport(channel, HOLDER);
        Recording listener = new Recording();
        reader.start(listener);
        assertTrue(listener.connected.await(5, TimeUnit.SECONDS));
        reader.send(new byte[]{1});
        Thread.sleep(100);

        reader.stop();
        Thread.sleep(200);

        assertFalse(channel.isLinkOpen());
        assertEquals(List.of("connected"), listener.events);
        assertThrows(ProximityException.class, () -> reader.send(new byte[]{2}));
    }

    @Test
    public void stopDoesNotBlockTheCallerOnAChannelWhoseCloseIsStuck() throws InterruptedException {
        CountDownLatch closeEntered = new CountDownLatch(1);
        CountDownLatch releaseClose = new CountDownLatch(1);
        ApduChannel stuck = new ApduChannel() {
            @NonNull
            @Override
            public byte[] transceive(@NonNull byte[] commandApdu) {
                return IsoAnnexD.hex("9000");
            }

            @Override
            public int maxTransceiveLength() {
                return 261;
            }

            @Override
            public void close() {
                closeEntered.countDown();
                try {
                    releaseClose.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        };
        NfcReaderTransport reader = new NfcReaderTransport(stuck, HOLDER);
        Recording listener = new Recording();
        reader.start(listener);
        assertTrue(listener.connected.await(5, TimeUnit.SECONDS));

        long before = System.nanoTime();
        reader.stop();
        long elapsedMillis = (System.nanoTime() - before) / 1_000_000L;

        assertTrue("stop() must return without waiting for close(), took " + elapsedMillis + " ms",
                elapsedMillis < 500);
        assertTrue(closeEntered.await(5, TimeUnit.SECONDS));
        releaseClose.countDown();
    }

    @Test
    public void messagesAreExchangedInOrderOnTheIoThread() throws InterruptedException {
        Echo[] echo = new Echo[1];
        HolderBackedChannel channel = new HolderBackedChannel(holder(echo));
        NfcReaderTransport reader = new NfcReaderTransport(channel, HOLDER);
        Recording listener = new Recording();
        AtomicInteger ioThreadMessages = new AtomicInteger();
        Transport.Listener counting = new Transport.Listener() {
            @Override
            public void onConnected() {
                listener.onConnected();
            }

            @Override
            public void onMessage(@NonNull byte[] message) {
                if (Thread.currentThread().getName().equals(NfcReaderTransport.IO_THREAD)) {
                    ioThreadMessages.incrementAndGet();
                }
                listener.onMessage(message);
            }

            @Override
            public void onError(@NonNull ProximityException error) {
                listener.onError(error);
            }

            @Override
            public void onDisconnected() {
                listener.onDisconnected();
            }
        };
        reader.start(counting);
        assertTrue(listener.connected.await(5, TimeUnit.SECONDS));

        reader.send(new byte[]{1});
        reader.send(new byte[]{2});
        await(listener.messages);
        await(listener.messages);

        List<byte[]> received = new ArrayList<>(echo[0].received);
        assertEquals(2, received.size());
        assertEquals(1, received.get(0)[0]);
        assertEquals(2, received.get(1)[0]);
        assertEquals(2, ioThreadMessages.get());
        reader.stop();
    }
}
