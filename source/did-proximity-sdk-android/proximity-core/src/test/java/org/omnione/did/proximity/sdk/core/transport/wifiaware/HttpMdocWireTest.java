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

package org.omnione.did.proximity.sdk.core.transport.wifiaware;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import androidx.annotation.NonNull;

import org.junit.After;
import org.junit.Test;
import org.omnione.did.proximity.sdk.core.ProximityException;
import org.omnione.did.proximity.sdk.core.session.SessionMessages;
import org.omnione.did.proximity.sdk.core.transport.Transport;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class HttpMdocWireTest {

    private static final long WAIT_MS = 5_000L;

    private static final class RecordingListener implements Transport.Listener {
        final List<byte[]> messages = new ArrayList<>();
        final List<String> timeline = new ArrayList<>();
        volatile ProximityException error;
        final CountDownLatch messageSeen = new CountDownLatch(1);
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
            messageSeen.countDown();
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

    private final ExecutorService serverThread = Executors.newSingleThreadExecutor();
    private final ExecutorService clientThread = Executors.newSingleThreadExecutor();

    private PipedInputStream serverIn;
    private PipedOutputStream clientOut;
    private PipedInputStream clientIn;
    private PipedOutputStream serverOut;

    private HttpMdocServerWire server;
    private HttpMdocClientWire client;
    private final RecordingListener holder = new RecordingListener();
    private final RecordingListener reader = new RecordingListener();

    private void connect() throws IOException {
        serverIn = new PipedInputStream(1 << 16);
        clientOut = new PipedOutputStream(serverIn);
        clientIn = new PipedInputStream(1 << 16);
        serverOut = new PipedOutputStream(clientIn);
        server = new HttpMdocServerWire(serverIn, serverOut, holder);
        client = new HttpMdocClientWire(clientIn, clientOut, "[fe80::1]", clientThread, reader);
        serverThread.execute(server::serve);
    }

    @After
    public void tearDown() {
        if (client != null) {
            client.close();
        }
        if (server != null) {
            server.close();
        }
        serverThread.shutdownNow();
        clientThread.shutdownNow();
    }

    private static byte[] ascii(String text) {
        return text.getBytes(StandardCharsets.ISO_8859_1);
    }

    private static void await(CountDownLatch latch) throws InterruptedException {
        assertTrue("timed out", latch.await(WAIT_MS, TimeUnit.MILLISECONDS));
    }

    @Test
    public void aRequestReachesTheHolderAndItsResponseReachesTheReader() throws Exception {
        connect();

        client.send(ascii("establishment"));
        await(holder.messageSeen);
        assertArrayEquals(ascii("establishment"), holder.messages.get(0));

        server.send(ascii("device-response"));
        await(reader.messageSeen);
        assertArrayEquals(ascii("device-response"), reader.messages.get(0));
        assertNull(holder.error);
        assertNull(reader.error);
    }

    private static void awaitMessages(RecordingListener listener, int count)
            throws InterruptedException {
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

    @Test
    public void twoRoundTripsShareOneConnection() throws Exception {
        connect();

        client.send(ascii("one"));
        awaitMessages(holder, 1);
        server.send(ascii("first"));
        awaitMessages(reader, 1);
        client.send(ascii("two"));
        awaitMessages(holder, 2);
        server.send(ascii("second"));
        awaitMessages(reader, 2);

        assertArrayEquals(ascii("two"), holder.messages.get(1));
        assertArrayEquals(ascii("second"), reader.messages.get(1));
    }

    @Test
    public void sendingWithoutAPendingRequestIsRejected() throws Exception {
        connect();

        assertThrows(ProximityException.class, () -> server.send(ascii("unsolicited")));
    }

    @Test
    public void aReaderTerminationIsAnsweredWithAnEmpty200AndClosed() throws Exception {
        connect();

        client.send(SessionMessages.termination());
        await(holder.messageSeen);

        assertEquals(SessionMessages.STATUS_SESSION_TERMINATION,
                (int) SessionMessages.parseStatus(holder.messages.get(0)));
        await(holder.disconnected);
        assertNull(holder.error);
        assertNull(reader.error);
        assertTrue(reader.timeline.isEmpty());
    }

    @Test
    public void aHolderTerminationTravelsAsTheResponseBody() throws Exception {
        connect();

        client.send(ascii("request"));
        await(holder.messageSeen);
        server.send(SessionMessages.termination());
        await(reader.messageSeen);

        assertEquals(SessionMessages.STATUS_SESSION_TERMINATION,
                (int) SessionMessages.parseStatus(reader.messages.get(0)));
    }

    @Test
    public void theReaderSeesEofAsADisconnect() throws Exception {
        connect();

        client.send(ascii("request"));
        await(holder.messageSeen);
        serverOut.close();
        await(reader.disconnected);

        assertNull(reader.error);
    }

    @Test
    public void closingTheServerWhileARequestIsPendingAnswersWithAnEmpty200() throws Exception {
        connect();

        client.send(ascii("request"));
        await(holder.messageSeen);
        server.close();
        await(reader.disconnected);

        assertTrue(reader.messages.isEmpty());
    }

    @Test
    public void theHolderSeesEofAsADisconnect() throws Exception {
        connect();

        clientOut.close();
        await(holder.disconnected);

        assertNull(holder.error);
    }

    @Test
    public void aMalformedRequestIsRejectedWithAStatusAndReportedAsAnError() throws Exception {
        connect();

        clientOut.write(ascii("POST /mdoc HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n"));
        clientOut.flush();
        await(holder.failed);

        assertNotNull(holder.error);
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int b;
        while ((b = clientIn.read()) >= 0) {
            head.write(b);
        }
        assertTrue(head.toString("ISO-8859-1").startsWith("HTTP/1.1 501 "));
    }

    @Test
    public void aNon200ResponseIsAnErrorForTheReader() throws Exception {
        clientIn = new PipedInputStream(1 << 16);
        serverOut = new PipedOutputStream(clientIn);
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        client = new HttpMdocClientWire(clientIn, sink, "[::1]", clientThread, reader);

        client.send(ascii("request"));
        serverOut.write(ascii("HTTP/1.1 500 Internal Server Error\r\nContent-Length: 0\r\n\r\n"));
        serverOut.flush();
        await(reader.failed);

        assertNotNull(reader.error);
        assertTrue(sink.toString("ISO-8859-1").startsWith("POST /mdoc HTTP/1.1\r\nHost: [::1]"));
    }

    @Test
    public void aTerminationTolerantReadSurvivesAnEmptyOrMissingResponse() throws Exception {
        for (byte[] answer : new byte[][]{new byte[0], HttpMdocFraming.emptyResponse()}) {
            ByteArrayOutputStream sink = new ByteArrayOutputStream();
            RecordingListener listener = new RecordingListener();
            HttpMdocClientWire wire = new HttpMdocClientWire(new ByteArrayInputStream(answer),
                    sink, "[::1]", Runnable::run, listener);

            wire.send(SessionMessages.termination());

            assertNull(listener.error);
            assertTrue(listener.messages.isEmpty());
            assertTrue(sink.size() > 0);
        }
    }

    @Test
    public void sendAfterCloseIsIgnored() throws Exception {
        connect();
        client.close();

        client.send(ascii("late"));

        Thread.sleep(50);
        assertTrue(holder.messages.isEmpty());
        assertNull(reader.error);
    }
}
