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
import androidx.annotation.Nullable;

import org.omnione.did.proximity.sdk.core.ProximityException;
import org.omnione.did.proximity.sdk.core.logger.ProximityLogger;
import org.omnione.did.proximity.sdk.core.transport.TerminationFrames;
import org.omnione.did.proximity.sdk.core.transport.Transport;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntConsumer;

public final class HttpMdocClientWire implements Closeable {

    public static final int TERMINATION_READ_TIMEOUT_MS = 2_000;

    private final InputStream in;
    private final OutputStream out;
    private final String hostHeader;
    private final Executor io;
    private final Transport.Listener listener;
    private final IntConsumer readTimeout;
    private final AtomicBoolean closed = new AtomicBoolean();

    public HttpMdocClientWire(@NonNull InputStream in,
                              @NonNull OutputStream out,
                              @NonNull String hostHeader,
                              @NonNull Executor io,
                              @NonNull Transport.Listener listener) {
        this(in, out, hostHeader, io, listener, null);
    }

    public HttpMdocClientWire(@NonNull InputStream in,
                              @NonNull OutputStream out,
                              @NonNull String hostHeader,
                              @NonNull Executor io,
                              @NonNull Transport.Listener listener,
                              @Nullable IntConsumer readTimeout) {
        this.in = in;
        this.out = out;
        this.hostHeader = hostHeader;
        this.io = io;
        this.listener = listener;
        this.readTimeout = readTimeout;
    }

    public void send(@NonNull byte[] frame) {
        if (closed.get()) {
            return;
        }
        try {
            io.execute(() -> exchange(frame));
        } catch (RejectedExecutionException e) {
            ProximityLogger.getInstance().d("http send dropped after close");
        }
    }

    private void exchange(@NonNull byte[] frame) {
        if (closed.get()) {
            return;
        }
        try {
            out.write(HttpMdocFraming.request(hostHeader, frame));
            out.flush();
            if (TerminationFrames.isTermination(frame)) {
                drainTerminationResponse();
                closeStreams();
                return;
            }
            byte[] response = HttpMdocFraming.readResponseBody(in);
            if (response == null || response.length == 0) {
                closeStreams();
                if (!closed.get()) {
                    listener.onDisconnected();
                }
                return;
            }
            listener.onMessage(response);
        } catch (ProximityException e) {
            fail(e);
        } catch (IOException e) {
            fail(new ProximityException("http exchange failed", e));
        }
    }

    private void drainTerminationResponse() {
        try {
            if (readTimeout != null) {
                readTimeout.accept(TERMINATION_READ_TIMEOUT_MS);
            }
            HttpMdocFraming.readResponseBody(in);
        } catch (IOException | RuntimeException e) {
            ProximityLogger.getInstance().d("termination response not read: " + e);
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
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
