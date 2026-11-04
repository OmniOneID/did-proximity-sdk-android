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
package org.omnione.did.proximity.sdk.core.transport.ble;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import androidx.annotation.NonNull;

import org.junit.After;
import org.junit.Test;
import org.omnione.did.proximity.sdk.core.ProximityException;
import org.omnione.did.proximity.sdk.core.transport.Transport;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class L2capWireTest {

    private static final long WAIT_MS = 5_000L;

    private static final class RecordingListener implements Transport.Listener {
        final List<byte[]> messages = new ArrayList<>();
        final List<String> timeline = new ArrayList<>();
        volatile ProximityException error;
        final CountDownLatch disconnected = new CountDownLatch(1);
        final CountDownLatch failed = new CountDownLatch(1);

        @Override
        public synchronized void onConnected() {
            timeline.add("connected");
        }

        @Override
        public synchronized void onMessage(@NonNull byte[] message) {
            messages.add(message);
            timeline.add("message");
        }

        @Override
        public synchronized void onError(@NonNull ProximityException e) {
            error = e;
            timeline.add("error");
            failed.countDown();
        }

        @Override
        public synchronized void onDisconnected() {
            timeline.add("disconnected");
            disconnected.countDown();
        }
    }

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

    private static final class BlockingInput extends InputStream {
        private final CountDownLatch released = new CountDownLatch(1);

        @Override
        public int read() throws IOException {
            try {
                released.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            throw new IOException("socket closed");
        }

        @Override
        public void close() {
            released.countDown();
        }
    }

    private PipedInputStream localIn;
    private PipedOutputStream localOut;
    private PipedInputStream peerIn;
    private PipedOutputStream peerOut;
    private L2capWire wire;
    private Channel channel;
    private final RecordingListener listener = new RecordingListener();

    private void connect() throws IOException {
        localIn = new PipedInputStream(1 << 16);
        peerOut = new PipedOutputStream(localIn);
        peerIn = new PipedInputStream(1 << 16);
        localOut = new PipedOutputStream(peerIn);
        channel = new Channel(localIn, localOut);
        wire = new L2capWire(localIn, localOut, channel, 0L);
        wire.serve(listener);
    }

    @After
    public void tearDown() {
        if (wire != null) {
            wire.close();
        }
    }

    private static byte[] bytes(int... values) {
        byte[] out = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = (byte) values[i];
        }
        return out;
    }

    private static void await(CountDownLatch latch) throws InterruptedException {
        assertTrue("timed out", latch.await(WAIT_MS, TimeUnit.MILLISECONDS));
    }

    private void awaitMessages(int count) throws InterruptedException {
        long deadline = System.currentTimeMillis() + WAIT_MS;
        while (System.currentTimeMillis() < deadline) {
            synchronized (listener) {
                if (listener.messages.size() >= count) {
                    return;
                }
            }
            Thread.sleep(10);
        }
        assertEquals(count, listener.messages.size());
    }

    private static void awaitClosed(Channel channel) throws InterruptedException {
        long deadline = System.currentTimeMillis() + WAIT_MS;
        while (channel.closes.get() == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertEquals(1, channel.closes.get());
    }

    @Test
    public void aSentMessageArrivesFramedAtThePeer() throws Exception {
        connect();

        wire.send(bytes(0xA1, 0x00));

        assertArrayEquals(bytes(0xA1, 0x00), L2capFraming.readFrame(peerIn));
    }

    @Test
    public void aReceivedFrameReachesTheListener() throws Exception {
        connect();

        peerOut.write(L2capFraming.frame(bytes(7, 8, 9)));
        peerOut.flush();

        awaitMessages(1);
        assertArrayEquals(bytes(7, 8, 9), listener.messages.get(0));
        assertNull(listener.error);
    }

    @Test
    public void sendOrderIsPreserved() throws Exception {
        connect();

        for (int i = 0; i < 50; i++) {
            wire.send(bytes(i));
        }

        for (int i = 0; i < 50; i++) {
            assertArrayEquals(bytes(i), L2capFraming.readFrame(peerIn));
        }
    }

    @Test
    public void aPeerCloseIsReportedAsDisconnected() throws Exception {
        connect();

        peerOut.close();

        await(listener.disconnected);
        assertNull(listener.error);
    }

    @Test
    public void aMalformedFrameIsReportedAsAnError() throws Exception {
        connect();

        peerOut.write(bytes(0x80, 0, 0, 0));
        peerOut.flush();

        await(listener.failed);
        assertFalse(listener.timeline.contains("disconnected"));
    }

    @Test
    public void aReadFailureAfterCloseIsNotAnError() throws Exception {
        BlockingInput in = new BlockingInput();
        PipedInputStream sink = new PipedInputStream(1 << 16);
        PipedOutputStream out = new PipedOutputStream(sink);
        channel = new Channel(in, out);
        wire = new L2capWire(in, out, channel, 0L);
        wire.serve(listener);

        wire.close();
        awaitClosed(channel);

        Thread.sleep(100);
        assertNull(listener.error);
        assertTrue(listener.timeline.isEmpty());
    }

    @Test
    public void closeIsIdempotentAndClosesTheChannelOnce() throws Exception {
        connect();

        wire.close();
        wire.close();
        awaitClosed(channel);

        Thread.sleep(50);
        assertEquals(1, channel.closes.get());
    }

    @Test
    public void closeHappensAfterTheLastQueuedWrite() throws Exception {
        connect();

        wire.send(bytes(1));
        wire.send(bytes(2));
        wire.close();

        assertArrayEquals(bytes(1), L2capFraming.readFrame(peerIn));
        assertArrayEquals(bytes(2), L2capFraming.readFrame(peerIn));
        awaitClosed(channel);
        assertNull(L2capFraming.readFrame(peerIn));
    }

    @Test
    public void aSendAfterCloseIsDropped() throws Exception {
        connect();

        wire.close();
        awaitClosed(channel);
        wire.send(bytes(1));

        assertNull(L2capFraming.readFrame(peerIn));
    }

    @Test
    public void closeWaitsTheDelayOnlyWhenSomethingWasWritten() throws Exception {
        localIn = new PipedInputStream(1 << 16);
        peerOut = new PipedOutputStream(localIn);
        peerIn = new PipedInputStream(1 << 16);
        localOut = new PipedOutputStream(peerIn);
        channel = new Channel(localIn, localOut);
        wire = new L2capWire(localIn, localOut, channel, 300L);
        wire.serve(listener);

        long start = System.currentTimeMillis();
        wire.close();
        awaitClosed(channel);
        assertTrue(System.currentTimeMillis() - start < 250L);
    }

    @Test
    public void closeAfterAWriteWaitsTheDelay() throws Exception {
        localIn = new PipedInputStream(1 << 16);
        peerOut = new PipedOutputStream(localIn);
        peerIn = new PipedInputStream(1 << 16);
        localOut = new PipedOutputStream(peerIn);
        channel = new Channel(localIn, localOut);
        wire = new L2capWire(localIn, localOut, channel, 300L);
        wire.serve(listener);

        wire.send(bytes(1));
        long start = System.currentTimeMillis();
        wire.close();
        awaitClosed(channel);
        assertTrue(System.currentTimeMillis() - start >= 250L);
    }
}
