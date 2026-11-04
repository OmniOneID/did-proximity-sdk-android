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

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.wifi.aware.AttachCallback;
import android.net.wifi.aware.DiscoverySessionCallback;
import android.net.wifi.aware.PeerHandle;
import android.net.wifi.aware.PublishConfig;
import android.net.wifi.aware.PublishDiscoverySession;
import android.net.wifi.aware.WifiAwareManager;
import android.net.wifi.aware.WifiAwareSession;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

import org.omnione.did.proximity.sdk.core.ProximityException;
import org.omnione.did.proximity.sdk.core.engagement.WifiOptions;
import org.omnione.did.proximity.sdk.core.logger.ProximityLogger;
import org.omnione.did.proximity.sdk.core.transport.Transport;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

@RequiresApi(Build.VERSION_CODES.Q)
public final class WifiAwarePublisherTransport implements Transport {

    private final Context context;
    private final String serviceName;
    private final String passphrase;
    private final ExecutorService io = Executors.newSingleThreadExecutor(
            runnable -> new Thread(runnable, WifiAwareNetworks.IO_THREAD));
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean stopped = new AtomicBoolean();
    private final AtomicBoolean peerSeen = new AtomicBoolean();

    private volatile Listener listener;
    private volatile WifiAwareSession awareSession;
    private volatile PublishDiscoverySession publishSession;
    private volatile ConnectivityManager.NetworkCallback networkCallback;
    private volatile ServerSocket serverSocket;
    private volatile Socket socket;
    private volatile HttpMdocServerWire wire;

    public WifiAwarePublisherTransport(@NonNull Context context,
                                       @NonNull byte[] eDeviceKeyBytes,
                                       @NonNull WifiOptions advertised) {
        this.context = context.getApplicationContext();
        this.serviceName = WifiAwareNames.serviceName(eDeviceKeyBytes);
        this.passphrase = WifiAwareNames.passphrase(eDeviceKeyBytes, advertised);
    }

    @Override
    @SuppressWarnings("MissingPermission")
    public void start(@NonNull Listener listener) {
        if (!started.compareAndSet(false, true)) {
            throw new ProximityException("the transport is already started");
        }
        this.listener = listener;
        WifiAwareManager manager = context.getSystemService(WifiAwareManager.class);
        if (manager == null || !manager.isAvailable()) {
            started.set(false);
            throw new ProximityException("Wi-Fi Aware is unavailable");
        }
        ProximityLogger.getInstance().d("publishing Wi-Fi Aware service " + serviceName);
        try {
            manager.attach(attachCallback, null);
        } catch (SecurityException e) {
            started.set(false);
            throw new ProximityException("Wi-Fi Aware permission is not granted", e);
        }
    }

    private final AttachCallback attachCallback = new AttachCallback() {
        @Override
        public void onAttachFailed() {
            report(new ProximityException("Wi-Fi Aware attach failed"));
        }

        @Override
        @SuppressWarnings("MissingPermission")
        public void onAttached(@NonNull WifiAwareSession session) {
            if (stopped.get()) {
                session.close();
                return;
            }
            awareSession = session;
            try {
                session.publish(new PublishConfig.Builder().setServiceName(serviceName).build(),
                        discoveryCallback, null);
            } catch (SecurityException e) {
                report(new ProximityException("Wi-Fi Aware publish permission is not granted", e));
            }
        }
    };

    private final DiscoverySessionCallback discoveryCallback = new DiscoverySessionCallback() {
        @Override
        public void onPublishStarted(@NonNull PublishDiscoverySession session) {
            if (stopped.get()) {
                session.close();
                return;
            }
            publishSession = session;
            ProximityLogger.getInstance().d("Wi-Fi Aware publish started");
        }

        @Override
        public void onSessionConfigFailed() {
            report(new ProximityException("Wi-Fi Aware publish failed"));
        }

        @Override
        public void onMessageReceived(@NonNull PeerHandle peer, @NonNull byte[] message) {
            if (!peerSeen.compareAndSet(false, true)) {
                return;
            }
            PublishDiscoverySession session = publishSession;
            if (session == null || stopped.get()) {
                return;
            }
            openDataPath(session, peer);
        }

        @Override
        public void onMessageSendFailed(int messageId) {
            report(new ProximityException("Wi-Fi Aware helloSub was not delivered"));
        }
    };

    @SuppressWarnings("MissingPermission")
    private void openDataPath(@NonNull PublishDiscoverySession session, @NonNull PeerHandle peer) {
        ServerSocket opened;
        try {
            opened = new ServerSocket(0);
        } catch (IOException e) {
            report(new ProximityException("could not open the mdoc http listener", e));
            return;
        }
        serverSocket = opened;
        ConnectivityManager connectivity = context.getSystemService(ConnectivityManager.class);
        if (connectivity == null) {
            report(new ProximityException("ConnectivityManager is unavailable"));
            return;
        }
        ConnectivityManager.NetworkCallback callback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onLost(@NonNull Network network) {
                ProximityLogger.getInstance().d("Wi-Fi Aware data path lost");
                HttpMdocServerWire active = wire;
                if (active != null) {
                    active.close();
                }
                if (!stopped.get() && active != null) {
                    Listener host = listener;
                    if (host != null) {
                        host.onDisconnected();
                    }
                }
            }
        };
        networkCallback = callback;
        connectivity.requestNetwork(
                WifiAwareNetworks.request(session, peer, passphrase, opened.getLocalPort()),
                callback);
        session.sendMessage(peer, 0, WifiAwareNetworks.HELLO_SUB);
        io.execute(() -> accept(opened));
    }

    private void accept(@NonNull ServerSocket listening) {
        Socket accepted;
        try {
            accepted = listening.accept();
        } catch (IOException e) {
            if (!stopped.get()) {
                report(new ProximityException("mdoc http accept failed", e));
            }
            return;
        }
        socket = accepted;
        Listener host = listener;
        HttpMdocServerWire opened;
        try {
            opened = new HttpMdocServerWire(accepted.getInputStream(), accepted.getOutputStream(),
                    host);
        } catch (IOException e) {
            report(new ProximityException("mdoc http socket streams failed", e));
            return;
        }
        wire = opened;
        if (stopped.get()) {
            opened.close();
            return;
        }
        ProximityLogger.getInstance().d("Wi-Fi Aware reader connected from "
                + accepted.getInetAddress());
        host.onConnected();
        opened.serve();
    }

    @Override
    public void send(@NonNull byte[] message) {
        HttpMdocServerWire active = wire;
        if (active == null) {
            throw new ProximityException("no pending http request");
        }
        active.send(message);
    }

    @Override
    public void stop() {
        if (!stopped.compareAndSet(false, true)) {
            return;
        }
        ProximityLogger.getInstance().d("stopping Wi-Fi Aware publisher");
        HttpMdocServerWire active = wire;
        if (active != null) {
            active.close();
        }
        closeQuietly(socket);
        ServerSocket listening = serverSocket;
        if (listening != null) {
            try {
                listening.close();
            } catch (IOException ignored) {
            }
        }
        ConnectivityManager.NetworkCallback callback = networkCallback;
        ConnectivityManager connectivity = context.getSystemService(ConnectivityManager.class);
        if (callback != null && connectivity != null) {
            try {
                connectivity.unregisterNetworkCallback(callback);
            } catch (RuntimeException e) {
                ProximityLogger.getInstance().d("network callback unregister failed: " + e);
            }
        }
        PublishDiscoverySession publish = publishSession;
        if (publish != null) {
            publish.close();
        }
        WifiAwareSession aware = awareSession;
        if (aware != null) {
            aware.close();
        }
        io.shutdownNow();
    }

    private void report(@NonNull ProximityException error) {
        if (stopped.get()) {
            return;
        }
        ProximityLogger.getInstance().e("Wi-Fi Aware publisher error: " + error);
        Listener host = listener;
        if (host != null) {
            host.onError(error);
        }
    }

    private static void closeQuietly(Socket socket) {
        if (socket == null) {
            return;
        }
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }
}
