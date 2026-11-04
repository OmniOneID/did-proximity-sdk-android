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

import androidx.annotation.NonNull;

import org.omnione.did.proximity.sdk.core.ProximityException;
import org.omnione.did.proximity.sdk.core.logger.ProximityLogger;
import org.omnione.did.proximity.sdk.core.transport.TerminationFrames;
import org.omnione.did.proximity.sdk.core.transport.Transport;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

public final class HttpMdocServerWire implements Closeable {

    private static final byte[] CLOSE = new byte[0];

    private final InputStream in;
    private final OutputStream out;
    private final Transport.Listener listener;
    private final BlockingQueue<byte[]> responses = new ArrayBlockingQueue<>(1);
    private final AtomicBoolean pending = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();

    public HttpMdocServerWire(@NonNull InputStream in,
                              @NonNull OutputStream out,
                              @NonNull Transport.Listener listener) {
        this.in = in;
        this.out = out;
        this.listener = listener;
    }

    public void serve() {
        try {
            while (!closed.get()) {
                byte[] body;
                try {
                    body = HttpMdocFraming.readRequestBody(in);
                } catch (HttpMdocFraming.RequestRejected e) {
                    writeQuietly(HttpMdocFraming.errorResponse(e.status()));
                    fail(e);
                    return;
                } catch (ProximityException e) {
                    fail(e);
                    return;
                } catch (IOException e) {
                    fail(new ProximityException("http request read failed", e));
                    return;
                }
                if (body == null) {
                    disconnect();
                    return;
                }
                if (TerminationFrames.isTermination(body)) {
                    writeQuietly(HttpMdocFraming.emptyResponse());
                    listener.onMessage(body);
                    disconnect();
                    return;
                }
                pending.set(true);
                listener.onMessage(body);
                byte[] response;
                try {
                    response = responses.take();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    writeQuietly(HttpMdocFraming.emptyResponse());
                    disconnect();
                    return;
                }
                if (response == CLOSE) {
                    writeQuietly(HttpMdocFraming.emptyResponse());
                    disconnect();
                    return;
                }
                try {
                    out.write(HttpMdocFraming.response(response));
                    out.flush();
                } catch (IOException e) {
                    fail(new ProximityException("http response write failed", e));
                    return;
                }
            }
        } finally {
            closeStreams();
        }
    }

    public void send(@NonNull byte[] frame) {
        if (!pending.compareAndSet(true, false)) {
            throw new ProximityException("no pending http request");
        }
        if (!responses.offer(frame)) {
            throw new ProximityException("a response is already queued");
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        if (pending.compareAndSet(true, false)) {
            responses.offer(CLOSE);
            return;
        }
        closeStreams();
    }

    private void fail(@NonNull ProximityException error) {
        closeStreams();
        if (!closed.get()) {
            listener.onError(error);
        }
    }

    private void disconnect() {
        closeStreams();
        if (!closed.get()) {
            listener.onDisconnected();
        }
    }

    private void writeQuietly(@NonNull byte[] bytes) {
        try {
            out.write(bytes);
            out.flush();
        } catch (IOException e) {
            ProximityLogger.getInstance().d("http write skipped: " + e);
        }
    }

    private void closeStreams() {
        try {
            out.close();
        } catch (IOException ignored) {
        }
        try {
            in.close();
        } catch (IOException ignored) {
        }
    }
}
