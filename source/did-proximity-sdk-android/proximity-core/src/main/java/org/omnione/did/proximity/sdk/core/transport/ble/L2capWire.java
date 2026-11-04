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

import androidx.annotation.NonNull;

import org.omnione.did.proximity.sdk.core.ProximityException;
import org.omnione.did.proximity.sdk.core.logger.ProximityLogger;
import org.omnione.did.proximity.sdk.core.transport.Transport;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

public final class L2capWire implements Closeable {

    public static final long DEFAULT_CLOSE_DELAY_MS = 2_000L;

    private final InputStream in;
    private final OutputStream out;
    private final Closeable channel;
    private final long closeDelayMs;
    private final ExecutorService writer = Executors.newSingleThreadExecutor(
            runnable -> daemon(runnable, "proximity-l2cap-write"));
    private final AtomicBoolean served = new AtomicBoolean();
    private final AtomicBoolean written = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean channelClosed = new AtomicBoolean();

    public L2capWire(@NonNull InputStream in,
                     @NonNull OutputStream out,
                     @NonNull Closeable channel,
                     long closeDelayMs) {
        this.in = in;
        this.out = out;
        this.channel = channel;
        this.closeDelayMs = closeDelayMs;
    }

    public void serve(@NonNull Transport.Listener listener) {
        if (!served.compareAndSet(false, true)) {
            throw new ProximityException("l2cap wire is already served");
        }
        daemon(() -> readLoop(listener), "proximity-l2cap-read").start();
    }

    public void send(@NonNull byte[] message) {
        byte[] frame = L2capFraming.frame(message);
        if (closed.get()) {
            return;
        }
        try {
            writer.execute(() -> write(frame));
        } catch (RejectedExecutionException e) {
            ProximityLogger.getInstance().d("l2cap send dropped after close");
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        try {
            writer.execute(this::closeChannel);
        } catch (RejectedExecutionException e) {
            closeChannel();
        }
        writer.shutdown();
    }

    private void readLoop(@NonNull Transport.Listener listener) {
        while (!closed.get()) {
            byte[] message;
            try {
                message = L2capFraming.readFrame(in);
            } catch (ProximityException e) {
                fail(listener, e);
                return;
            } catch (IOException e) {
                fail(listener, new ProximityException("l2cap read failed", e));
                return;
            }
            if (message == null) {
                if (!closed.get()) {
                    listener.onDisconnected();
                }
                return;
            }
            if (closed.get()) {
                return;
            }
            listener.onMessage(message);
        }
    }

    private void write(@NonNull byte[] frame) {
        written.set(true);
        try {
            out.write(frame);
            out.flush();
        } catch (IOException e) {
            ProximityLogger.getInstance().d("l2cap write failed: " + e);
            written.set(false);
            closeChannel();
        }
    }

    private void fail(@NonNull Transport.Listener listener, @NonNull ProximityException error) {
        if (closed.get()) {
            return;
        }
        listener.onError(error);
    }

    private void closeChannel() {
        if (!channelClosed.compareAndSet(false, true)) {
            return;
        }
        if (written.get() && closeDelayMs > 0) {
            try {
                Thread.sleep(closeDelayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        try {
            channel.close();
        } catch (IOException e) {
            ProximityLogger.getInstance().d("l2cap channel close failed: " + e);
        }
    }

    @NonNull
    private static Thread daemon(@NonNull Runnable runnable, @NonNull String name) {
        Thread thread = new Thread(runnable, name);
        thread.setDaemon(true);
        return thread;
    }
}
