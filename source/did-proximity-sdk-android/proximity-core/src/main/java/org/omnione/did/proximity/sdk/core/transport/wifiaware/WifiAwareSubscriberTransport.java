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
import android.net.NetworkCapabilities;
import android.net.wifi.aware.AttachCallback;
import android.net.wifi.aware.DiscoverySessionCallback;
import android.net.wifi.aware.PeerHandle;
import android.net.wifi.aware.SubscribeConfig;
import android.net.wifi.aware.SubscribeDiscoverySession;
import android.net.wifi.aware.WifiAwareManager;
import android.net.wifi.aware.WifiAwareNetworkInfo;
import android.net.wifi.aware.WifiAwareSession;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

import org.omnione.did.proximity.sdk.core.ProximityException;
import org.omnione.did.proximity.sdk.core.engagement.WifiOptions;
import org.omnione.did.proximity.sdk.core.logger.ProximityLogger;
import org.omnione.did.proximity.sdk.core.transport.Transport;

import java.io.IOException;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

@RequiresApi(Build.VERSION_CODES.Q)
public final class WifiAwareSubscriberTransport implements Transport {

    static final int CONNECT_TIMEOUT_MS = 10_000;

    private final Context context;
    private final String serviceName;
    private final String passphrase;
    private final ExecutorService io = Executors.newSingleThreadExecutor(
            runnable -> new Thread(runnable, WifiAwareNetworks.IO_THREAD));
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean stopped = new AtomicBoolean();
    private final AtomicBoolean requested = new AtomicBoolean();
    private final AtomicBoolean connecting = new AtomicBoolean();

    private volatile Listener listener;
    private volatile WifiAwareSession awareSession;
    private volatile SubscribeDiscoverySession subscribeSession;
    private volatile ConnectivityManager.NetworkCallback networkCallback;
    private volatile Socket socket;
    private volatile HttpMdocClientWire wire;

    public WifiAwareSubscriberTransport(@NonNull Context context,
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
        ProximityLogger.getInstance().d("subscribing to Wi-Fi Aware service " + serviceName);
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
                session.subscribe(
                        new SubscribeConfig.Builder().setServiceName(serviceName).build(),
                        discoveryCallback, null);
            } catch (SecurityException e) {
                report(new ProximityException(
                        "Wi-Fi Aware subscribe permission is not granted", e));
            }
        }
    };

    private final DiscoverySessionCallback discoveryCallback = new DiscoverySessionCallback() {
        @Override
        public void onSubscribeStarted(@NonNull SubscribeDiscoverySession session) {
            if (stopped.get()) {
                session.close();
                return;
            }
            subscribeSession = session;
            ProximityLogger.getInstance().d("Wi-Fi Aware subscribe started");
        }

        @Override
        public void onSessionConfigFailed() {
            report(new ProximityException("Wi-Fi Aware subscribe failed"));
        }

        @Override
        public void onServiceDiscovered(@NonNull PeerHandle peer,
                                        byte[] serviceSpecificInfo,
                                        List<byte[]> matchFilter) {
            SubscribeDiscoverySession session = subscribeSession;
            if (session == null || stopped.get() || requested.get()) {
                return;
            }
            ProximityLogger.getInstance().d("Wi-Fi Aware mdoc service discovered");
            session.sendMessage(peer, 0, WifiAwareNetworks.HELLO_PUB);
        }

        @Override
        public void onMessageSendFailed(int messageId) {
            report(new ProximityException("Wi-Fi Aware helloPub was not delivered"));
        }

        @Override
        public void onMessageReceived(@NonNull PeerHandle peer, @NonNull byte[] message) {
            if (!requested.compareAndSet(false, true)) {
                return;
            }
            SubscribeDiscoverySession session = subscribeSession;
            if (session == null || stopped.get()) {
                return;
            }
            requestDataPath(session, peer);
        }
    };

    @SuppressWarnings("MissingPermission")
    private void requestDataPath(@NonNull SubscribeDiscoverySession session,
                                 @NonNull PeerHandle peer) {
        ConnectivityManager connectivity = context.getSystemService(ConnectivityManager.class);
        if (connectivity == null) {
            report(new ProximityException("ConnectivityManager is unavailable"));
            return;
        }
        ConnectivityManager.NetworkCallback callback = new ConnectivityManager.NetworkCallback() {
            private volatile boolean available;
            private volatile NetworkCapabilities capabilities;

            @Override
            public void onAvailable(@NonNull Network network) {
                available = true;
                connectWhenReady(network, capabilities);
            }

            @Override
            public void onCapabilitiesChanged(@NonNull Network network,
                                              @NonNull NetworkCapabilities networkCapabilities) {
                capabilities = networkCapabilities;
                if (available) {
                    connectWhenReady(network, networkCapabilities);
                }
            }

            @Override
            public void onLost(@NonNull Network network) {
                ProximityLogger.getInstance().d("Wi-Fi Aware data path lost");
                HttpMdocClientWire active = wire;
                if (active != null) {
                    active.close();
                }
                if (!stopped.get() && connecting.get()) {
                    Listener host = listener;
                    if (host != null) {
                        host.onDisconnected();
                    }
                }
            }

            @Override
            public void onUnavailable() {
                report(new ProximityException("Wi-Fi Aware data path was refused"));
            }
        };
        networkCallback = callback;
        connectivity.requestNetwork(WifiAwareNetworks.request(session, peer, passphrase, 0),
                callback);
    }

    private void connectWhenReady(@NonNull Network network, NetworkCapabilities capabilities) {
        if (capabilities == null || stopped.get()) {
            return;
        }
        if (!(capabilities.getTransportInfo() instanceof WifiAwareNetworkInfo)) {
            return;
        }
        if (!connecting.compareAndSet(false, true)) {
            return;
        }
        WifiAwareNetworkInfo info = (WifiAwareNetworkInfo) capabilities.getTransportInfo();
        Inet6Address peer = info.getPeerIpv6Addr();
        int port = info.getPort();
        if (peer == null || port <= 0) {
            report(new ProximityException("Wi-Fi Aware peer did not publish an http port"));
            return;
        }
        io.execute(() -> connect(network, peer, port));
    }

    private void connect(@NonNull Network network, @NonNull Inet6Address peer, int port) {
        Socket opened;
        String host;
        try {
            host = "[" + InetAddress.getByAddress(peer.getAddress()).getHostAddress() + "]";
            opened = network.getSocketFactory().createSocket();
            opened.connect(new InetSocketAddress(peer, port), CONNECT_TIMEOUT_MS);
        } catch (UnknownHostException e) {
            report(new ProximityException("Wi-Fi Aware peer address is malformed", e));
            return;
        } catch (IOException e) {
            if (!stopped.get()) {
                report(new ProximityException("mdoc http connect failed", e));
            }
            return;
        }
        socket = opened;
        Listener hostListener = listener;
        HttpMdocClientWire active;
        try {
            active = new HttpMdocClientWire(opened.getInputStream(), opened.getOutputStream(),
                    host, io, hostListener, timeoutMs -> setReadTimeout(opened, timeoutMs));
        } catch (IOException e) {
            report(new ProximityException("mdoc http socket streams failed", e));
            return;
        }
        wire = active;
        if (stopped.get()) {
            active.close();
            return;
        }
        ProximityLogger.getInstance().d("Wi-Fi Aware mdoc connected at " + host + ":" + port);
        hostListener.onConnected();
    }

    private static void setReadTimeout(@NonNull Socket socket, int timeoutMs) {
        try {
            socket.setSoTimeout(timeoutMs);
        } catch (IOException e) {
            ProximityLogger.getInstance().d("read timeout not applied: " + e);
        }
    }

    @Override
    public void send(@NonNull byte[] message) {
        HttpMdocClientWire active = wire;
        if (active == null) {
            throw new ProximityException("the mdoc is not connected");
        }
        active.send(message);
    }

    @Override
    public void stop() {
        if (!stopped.compareAndSet(false, true)) {
            return;
        }
        ProximityLogger.getInstance().d("stopping Wi-Fi Aware subscriber");
        HttpMdocClientWire active = wire;
        if (active != null) {
            active.close();
        }
        Socket opened = socket;
        if (opened != null) {
            try {
                opened.close();
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
        SubscribeDiscoverySession subscribe = subscribeSession;
        if (subscribe != null) {
            subscribe.close();
        }
        WifiAwareSession aware = awareSession;
        if (aware != null) {
            aware.close();
        }
        io.shutdown();
    }

    private void report(@NonNull ProximityException error) {
        if (stopped.get()) {
            return;
        }
        ProximityLogger.getInstance().e("Wi-Fi Aware subscriber error: " + error);
        Listener host = listener;
        if (host != null) {
            host.onError(error);
        }
    }
}
