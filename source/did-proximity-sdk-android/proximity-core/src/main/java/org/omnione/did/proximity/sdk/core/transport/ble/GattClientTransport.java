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

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothSocket;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.os.Build;
import android.os.ParcelUuid;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import org.omnione.did.proximity.sdk.core.ProximityException;
import org.omnione.did.proximity.sdk.core.logger.ProximityLogger;
import org.omnione.did.proximity.sdk.core.transport.ChunkAssembler;
import org.omnione.did.proximity.sdk.core.transport.Transport;

import java.io.IOException;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class GattClientTransport implements Transport {

    private static final int DEFAULT_MTU = 23;
    private static final int REQUESTED_MTU = 517;
    private static final int MAX_RECONNECTS = 3;

    private final Context context;
    private final GattProfile profile;
    private final byte[] expectedIdent;
    private final UUID serviceUuid;
    private final GattClientHandshake handshake;
    private final Integer engagementPsm;
    private final boolean useL2cap;
    private final ExecutorService l2capConnector = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "proximity-l2cap-connect");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean started = new AtomicBoolean();
    private final Object sendLock = new Object();
    private final Queue<byte[]> sendQueue = new ArrayDeque<>();
    private final AtomicInteger reconnects = new AtomicInteger();

    private boolean sending;
    private volatile ChunkAssembler assembler = new ChunkAssembler();
    private volatile Listener listener;
    private volatile BluetoothLeScanner scanner;
    private volatile BluetoothGatt gatt;
    private volatile BluetoothGattCharacteristic clientToServer;
    private volatile BluetoothGattCharacteristic state;
    private volatile BluetoothGattCharacteristic serverToClient;
    private volatile BluetoothGattCharacteristic ident;
    private volatile BluetoothGattCharacteristic l2capCharacteristic;
    private volatile BluetoothSocket connectingSocket;
    private volatile L2capWire wire;

    public GattClientTransport(@NonNull Context context, @NonNull UUID serviceUuid) {
        this(context, serviceUuid, GattProfile.MDOC, null);
    }

    public GattClientTransport(@NonNull Context context,
                               @NonNull UUID serviceUuid,
                               @NonNull GattProfile profile,
                               @Nullable byte[] expectedIdent) {
        this(context, serviceUuid, profile, expectedIdent, null, false);
    }

    public GattClientTransport(@NonNull Context context,
                               @NonNull UUID serviceUuid,
                               @NonNull GattProfile profile,
                               @Nullable byte[] expectedIdent,
                               @Nullable Integer engagementPsm,
                               boolean useL2cap) {
        boolean verifiesIdent = profile.ident() != null;
        if (verifiesIdent && expectedIdent == null) {
            throw new ProximityException(
                    "a GATT profile with an ident characteristic requires an ident value");
        }
        if (!verifiesIdent && expectedIdent != null) {
            throw new ProximityException(
                    "a GATT profile without an ident characteristic takes no ident value");
        }
        this.context = context.getApplicationContext();
        this.serviceUuid = serviceUuid;
        this.profile = profile;
        this.expectedIdent = expectedIdent == null ? null : expectedIdent.clone();
        this.handshake = new GattClientHandshake(DEFAULT_MTU, verifiesIdent);
        this.useL2cap = useL2cap && Build.VERSION.SDK_INT >= L2capSupport.MIN_SDK;
        this.engagementPsm = this.useL2cap && engagementPsm != null
                ? L2capFraming.requirePsm(engagementPsm) : null;
    }

    @Override
    @SuppressWarnings("MissingPermission")
    public void start(@NonNull Listener listener) {
        if (!started.compareAndSet(false, true)) {
            throw new ProximityException("the transport is already started");
        }
        this.listener = listener;

        BluetoothManager manager = context.getSystemService(BluetoothManager.class);
        if (manager == null || manager.getAdapter() == null) {
            started.set(false);
            throw new ProximityException("BluetoothManager is unavailable");
        }
        BluetoothAdapter adapter = manager.getAdapter();
        BluetoothLeScanner opened = adapter.getBluetoothLeScanner();
        if (opened == null) {
            started.set(false);
            throw new ProximityException("Bluetooth is off");
        }
        scanner = opened;

        ProximityLogger.getInstance().d("scanning for the mdoc service " + serviceUuid);
        try {
            opened.startScan(scanFilters(serviceUuid), scanSettings(), scanCallback);
        } catch (SecurityException e) {
            started.set(false);
            throw new ProximityException("BLUETOOTH_SCAN permission is not granted", e);
        }
    }

    @NonNull
    private static List<ScanFilter> scanFilters(@NonNull UUID serviceUuid) {
        return Collections.singletonList(new ScanFilter.Builder()
                .setServiceUuid(new ParcelUuid(serviceUuid))
                .build());
    }

    @NonNull
    private static ScanSettings scanSettings() {
        return new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();
    }

    @SuppressWarnings("MissingPermission")
    private boolean restartScan() {
        UUID service = serviceUuid;
        if (service == null || reconnects.incrementAndGet() > MAX_RECONNECTS) {
            return false;
        }
        BluetoothManager manager = context.getSystemService(BluetoothManager.class);
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        BluetoothLeScanner opened = adapter == null ? null : adapter.getBluetoothLeScanner();
        if (opened == null) {
            return false;
        }
        scanner = opened;
        ProximityLogger.getInstance().d("the connection dropped before the transfer started,"
                + " scanning again for " + service);
        try {
            opened.startScan(scanFilters(service), scanSettings(), scanCallback);
            return true;
        } catch (SecurityException | IllegalStateException e) {
            scanner = null;
            return false;
        }
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        @SuppressWarnings("MissingPermission")
        public void onScanResult(int callbackType, ScanResult result) {
            if (!started.get()) {
                return;
            }
            Integer psm = engagementPsm;
            GattClientHandshake.Action action = psm != null
                    ? handshake.onDeviceFoundWithPsm() : handshake.onDeviceFound();
            BluetoothDevice device = result.getDevice();
            if (action == GattClientHandshake.Action.CONNECT_L2CAP) {
                stopScan();
                connectL2cap(device, psm, "engagement");
                return;
            }
            if (action != GattClientHandshake.Action.CONNECT) {
                return;
            }
            stopScan();
            connectGatt(device);
        }

        @Override
        public void onScanFailed(int errorCode) {
            if (!started.get()) {
                return;
            }
            report(new ProximityException("the BLE scan failed with error " + errorCode));
        }
    };

    @SuppressWarnings("MissingPermission")
    private void connectGatt(@NonNull BluetoothDevice device) {
        ProximityLogger.getInstance().d("connecting to " + device);
        try {
            BluetoothGatt opened = device.connectGatt(context, false, gattCallback,
                    BluetoothDevice.TRANSPORT_LE);
            gatt = opened;
            if (opened != null && !started.get()) {
                releaseGatt(opened);
            }
        } catch (SecurityException e) {
            report(new ProximityException("BLUETOOTH_CONNECT permission is not granted", e));
        }
    }

    private void connectL2cap(@NonNull BluetoothDevice device, int psm, @NonNull String via) {
        if (Build.VERSION.SDK_INT < L2capSupport.MIN_SDK) {
            fallBackFromL2cap(device);
            return;
        }
        ProximityLogger.getInstance().d("connecting l2cap psm=" + psm + " via=" + via);
        try {
            l2capConnector.execute(() -> openL2cap(device, psm));
        } catch (RejectedExecutionException e) {
            ProximityLogger.getInstance().d("l2cap connect dropped after stop");
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    @SuppressWarnings("MissingPermission")
    private void openL2cap(@NonNull BluetoothDevice device, int psm) {
        BluetoothSocket socket;
        try {
            socket = device.createInsecureL2capChannel(psm);
            connectingSocket = socket;
            if (!started.get()) {
                closeQuietly(socket);
                return;
            }
            socket.connect();
        } catch (IOException | SecurityException e) {
            connectingSocket = null;
            ProximityLogger.getInstance().d("l2cap connect failed psm=" + psm + ": " + e);
            fallBackFromL2cap(device);
            return;
        }
        connectingSocket = null;
        if (!started.get() || handshake.onL2capConnected() != GattClientHandshake.Action.NONE
                || !handshake.isStarted()) {
            closeQuietly(socket);
            return;
        }
        L2capWire opened;
        try {
            opened = new L2capWire(socket.getInputStream(), socket.getOutputStream(), socket,
                    L2capWire.DEFAULT_CLOSE_DELAY_MS);
        } catch (IOException e) {
            closeQuietly(socket);
            report(new ProximityException("l2cap socket streams unavailable", e));
            return;
        }
        wire = opened;
        ProximityLogger.getInstance().d("handshake complete transport=l2cap psm=" + psm);
        opened.serve(wireListener);
        Listener host = listener;
        if (host != null) {
            host.onConnected();
        }
    }

    @SuppressWarnings("MissingPermission")
    private void fallBackFromL2cap(@NonNull BluetoothDevice device) {
        if (!started.get()) {
            return;
        }
        GattClientHandshake.Action action = handshake.onL2capFailed();
        if (action == GattClientHandshake.Action.CONNECT) {
            ProximityLogger.getInstance().d("falling back to gatt");
            connectGatt(device);
            return;
        }
        if (action == GattClientHandshake.Action.SUBSCRIBE_STATE) {
            ProximityLogger.getInstance().d("falling back to gatt over the open link");
            BluetoothGatt target = gatt;
            BluetoothGattCharacteristic characteristicState = state;
            if (target == null || characteristicState == null) {
                report(new ProximityException("the state characteristic is unavailable"));
                return;
            }
            try {
                subscribe(target, characteristicState);
            } catch (SecurityException e) {
                report(new ProximityException("BLUETOOTH_CONNECT permission is not granted", e));
            } catch (RuntimeException e) {
                report(wrap(e));
            }
        }
    }

    private final Listener wireListener = new Listener() {
        @Override
        public void onConnected() {
        }

        @Override
        public void onMessage(@NonNull byte[] message) {
            Listener host = listener;
            if (host != null) {
                host.onMessage(message);
            }
        }

        @Override
        public void onError(@NonNull ProximityException error) {
            report(error);
        }

        @Override
        public void onDisconnected() {
            Listener host = listener;
            if (host != null) {
                host.onDisconnected();
            }
        }
    };

    private static void closeQuietly(@NonNull BluetoothSocket socket) {
        try {
            socket.close();
        } catch (IOException e) {
            ProximityLogger.getInstance().d("l2cap socket close failed: " + e);
        }
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        @SuppressWarnings("MissingPermission")
        public void onConnectionStateChange(BluetoothGatt target, int status, int newState) {
            if (target != gatt) {
                return;
            }
            try {
                if (newState == BluetoothGatt.STATE_CONNECTED) {
                    if (handshake.onConnected() == GattClientHandshake.Action.DISCOVER_SERVICES) {
                        target.discoverServices();
                    }
                    return;
                }
                if (newState != BluetoothGatt.STATE_DISCONNECTED) {
                    return;
                }
                if (wire != null) {
                    ProximityLogger.getInstance().d("gatt link dropped while l2cap is active");
                    releaseGatt(target);
                    return;
                }
                GattClientHandshake.Action action = handshake.onConnectionLost();
                releaseGatt(target);
                if (action == GattClientHandshake.Action.ABORT) {
                    Listener host = listener;
                    if (host != null) {
                        host.onDisconnected();
                    }
                    return;
                }
                if (action == GattClientHandshake.Action.RECONNECT
                        && started.get() && restartScan()) {
                    return;
                }
                report(new ProximityException(
                        "the connection dropped before the transfer started"));
            } catch (SecurityException e) {
                report(new ProximityException("BLUETOOTH_CONNECT permission is not granted", e));
            } catch (RuntimeException e) {
                report(wrap(e));
            }
        }

        @Override
        @SuppressWarnings("MissingPermission")
        public void onServicesDiscovered(BluetoothGatt target, int status) {
            if (target != gatt) {
                return;
            }
            try {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    report(new ProximityException(
                            "service discovery failed with status " + status));
                    return;
                }
                BluetoothGattService service = target.getService(serviceUuid);
                if (service == null) {
                    report(new ProximityException("the peer does not expose the mdoc service"));
                    return;
                }
                state = service.getCharacteristic(profile.state());
                clientToServer = service.getCharacteristic(profile.clientToServer());
                serverToClient = service.getCharacteristic(profile.serverToClient());
                if (state == null || clientToServer == null || serverToClient == null) {
                    report(new ProximityException("the mdoc service is missing a characteristic"));
                    return;
                }
                UUID identUuid = profile.ident();
                if (identUuid != null) {
                    ident = service.getCharacteristic(identUuid);
                    if (ident == null) {
                        report(new ProximityException(
                                "the mdoc reader service is missing the ident characteristic"));
                        return;
                    }
                }
                UUID l2capUuid = profile.l2cap();
                l2capCharacteristic = useL2cap && l2capUuid != null
                        ? service.getCharacteristic(l2capUuid) : null;
                GattClientHandshake.Action discovered =
                        handshake.onServicesDiscovered(l2capCharacteristic != null);
                if (discovered == GattClientHandshake.Action.READ_IDENT) {
                    ProximityLogger.getInstance().d("reading the mdoc reader ident");
                    target.readCharacteristic(ident);
                    return;
                }
                if (discovered == GattClientHandshake.Action.READ_PSM) {
                    readPsm(target);
                    return;
                }
                if (discovered == GattClientHandshake.Action.SUBSCRIBE_STATE) {
                    subscribe(target, state);
                }
            } catch (SecurityException e) {
                report(new ProximityException("BLUETOOTH_CONNECT permission is not granted", e));
            } catch (RuntimeException e) {
                report(wrap(e));
            }
        }

        @Override
        @SuppressWarnings("MissingPermission")
        public void onCharacteristicRead(BluetoothGatt target,
                                         BluetoothGattCharacteristic characteristic, int status) {
            if (target != gatt) {
                return;
            }
            UUID l2capUuid = profile.l2cap();
            if (l2capUuid != null && l2capUuid.equals(characteristic.getUuid())) {
                onPsmRead(target, characteristic, status);
                return;
            }
            UUID identUuid = profile.ident();
            if (identUuid == null || !identUuid.equals(characteristic.getUuid())) {
                return;
            }
            try {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    report(new ProximityException(
                            "reading the ident characteristic failed with status " + status));
                    return;
                }
                byte[] value = characteristic.getValue();
                if (value == null || !MessageDigest.isEqual(expectedIdent, value)) {
                    ProximityLogger.getInstance().e("mdoc reader ident mismatch, expected "
                            + expectedIdent.length + " bytes, got "
                            + (value == null ? "none" : value.length + " bytes"));
                    report(new ProximityException(
                            "the mdoc reader ident does not match the expected value"));
                    return;
                }
                ProximityLogger.getInstance().d("mdoc reader ident verified, "
                        + value.length + " bytes");
                GattClientHandshake.Action next = handshake.onIdentRead();
                if (next == GattClientHandshake.Action.READ_PSM) {
                    readPsm(target);
                    return;
                }
                if (next != GattClientHandshake.Action.SUBSCRIBE_STATE) {
                    return;
                }
                BluetoothGattCharacteristic characteristicState = state;
                if (characteristicState == null) {
                    report(new ProximityException("the state characteristic is unavailable"));
                    return;
                }
                subscribe(target, characteristicState);
            } catch (SecurityException e) {
                report(new ProximityException("BLUETOOTH_CONNECT permission is not granted", e));
            } catch (RuntimeException e) {
                report(wrap(e));
            }
        }

        @SuppressWarnings("MissingPermission")
        private void readPsm(@NonNull BluetoothGatt target) {
            BluetoothGattCharacteristic characteristic = l2capCharacteristic;
            if (characteristic == null) {
                onPsmRead(target, null, BluetoothGatt.GATT_FAILURE);
                return;
            }
            ProximityLogger.getInstance().d("reading the l2cap psm characteristic");
            if (!target.readCharacteristic(characteristic)) {
                onPsmRead(target, null, BluetoothGatt.GATT_FAILURE);
            }
        }

        @SuppressWarnings("MissingPermission")
        private void onPsmRead(@NonNull BluetoothGatt target,
                               @Nullable BluetoothGattCharacteristic characteristic,
                               int status) {
            Integer psm = null;
            if (status == BluetoothGatt.GATT_SUCCESS && characteristic != null) {
                byte[] value = characteristic.getValue();
                try {
                    psm = value == null ? null : L2capFraming.decodePsm(value);
                } catch (ProximityException e) {
                    ProximityLogger.getInstance().d("l2cap psm characteristic unusable: "
                            + e.getMessage());
                }
            } else {
                ProximityLogger.getInstance().d("l2cap psm characteristic read failed, status="
                        + status);
            }
            try {
                GattClientHandshake.Action action = handshake.onPsmRead(psm);
                if (action == GattClientHandshake.Action.CONNECT_L2CAP && psm != null) {
                    connectL2cap(target.getDevice(), psm, "characteristic");
                    return;
                }
                if (action == GattClientHandshake.Action.SUBSCRIBE_STATE) {
                    BluetoothGattCharacteristic characteristicState = state;
                    if (characteristicState == null) {
                        report(new ProximityException("the state characteristic is unavailable"));
                        return;
                    }
                    subscribe(target, characteristicState);
                }
            } catch (SecurityException e) {
                report(new ProximityException("BLUETOOTH_CONNECT permission is not granted", e));
            } catch (RuntimeException e) {
                report(wrap(e));
            }
        }

        @Override
        @SuppressWarnings("MissingPermission")
        public void onDescriptorWrite(BluetoothGatt target, BluetoothGattDescriptor descriptor,
                                      int status) {
            if (target != gatt) {
                return;
            }
            try {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    report(new ProximityException(
                            "enabling notifications failed with status " + status));
                    return;
                }
                GattClientHandshake.Action action = handshake.onDescriptorWritten();
                if (action == GattClientHandshake.Action.SUBSCRIBE_SERVER_TO_CLIENT) {
                    BluetoothGattCharacteristic characteristic = serverToClient;
                    if (characteristic == null) {
                        report(new ProximityException(
                                "the server to client characteristic is unavailable"));
                        return;
                    }
                    subscribe(target, characteristic);
                    return;
                }
                if (action == GattClientHandshake.Action.REQUEST_MTU) {
                    target.requestMtu(REQUESTED_MTU);
                }
            } catch (SecurityException e) {
                report(new ProximityException("BLUETOOTH_CONNECT permission is not granted", e));
            } catch (RuntimeException e) {
                report(wrap(e));
            }
        }

        @Override
        @SuppressWarnings("MissingPermission")
        public void onMtuChanged(BluetoothGatt target, int mtu, int status) {
            if (target != gatt) {
                return;
            }
            try {
                int negotiated = status == BluetoothGatt.GATT_SUCCESS ? mtu : 0;
                if (handshake.onMtuChanged(negotiated) != GattClientHandshake.Action.WRITE_START) {
                    return;
                }
                BluetoothGattCharacteristic characteristic = state;
                if (characteristic == null) {
                    report(new ProximityException("the state characteristic is unavailable"));
                    return;
                }
                ProximityLogger.getInstance().d("handshake complete transport=gatt mtu="
                        + handshake.mtu());
                characteristic.setValue(new byte[]{BleUuids.STATE_START});
                characteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
                target.writeCharacteristic(characteristic);
            } catch (SecurityException e) {
                report(new ProximityException("BLUETOOTH_CONNECT permission is not granted", e));
            } catch (RuntimeException e) {
                report(wrap(e));
            }
        }

        @Override
        @SuppressWarnings("MissingPermission")
        public void onCharacteristicWrite(BluetoothGatt target,
                                          BluetoothGattCharacteristic characteristic, int status) {
            if (target != gatt) {
                return;
            }
            try {
                if (profile.state().equals(characteristic.getUuid())) {
                    handshake.onStartWritten();
                    Listener host = listener;
                    if (host != null) {
                        host.onConnected();
                    }
                    return;
                }
                if (profile.clientToServer().equals(characteristic.getUuid())) {
                    drain(target, status);
                }
            } catch (SecurityException e) {
                report(new ProximityException("BLUETOOTH_CONNECT permission is not granted", e));
            } catch (RuntimeException e) {
                report(wrap(e));
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt target,
                                            BluetoothGattCharacteristic characteristic) {
            if (target != gatt) {
                return;
            }
            byte[] value = characteristic.getValue();
            if (value == null) {
                return;
            }
            if (profile.state().equals(characteristic.getUuid())) {
                if (value.length > 0 && value[0] == BleUuids.STATE_END) {
                    Listener host = listener;
                    if (host != null) {
                        host.onDisconnected();
                    }
                }
                return;
            }
            if (!profile.serverToClient().equals(characteristic.getUuid())) {
                return;
            }
            try {
                byte[] message = assembler.append(value);
                if (message != null) {
                    Listener host = listener;
                    if (host != null) {
                        host.onMessage(message);
                    }
                }
            } catch (RuntimeException e) {
                report(wrap(e));
            }
        }
    };

    @Override
    @SuppressWarnings("MissingPermission")
    public void send(@NonNull byte[] message) {
        L2capWire active = wire;
        if (active != null) {
            ProximityLogger.getInstance().d("send " + message.length + " bytes over l2cap");
            active.send(message);
            return;
        }
        BluetoothGatt target = gatt;
        BluetoothGattCharacteristic characteristic = clientToServer;
        if (target == null || characteristic == null) {
            throw new ProximityException("no mdoc is connected");
        }
        List<byte[]> chunks = ChunkAssembler.split(message,
                ChunkAssembler.maxChunkSize(handshake.mtu()));
        byte[] first;
        synchronized (sendLock) {
            sendQueue.addAll(chunks);
            if (sending) {
                return;
            }
            sending = true;
            first = sendQueue.poll();
        }
        writeChunk(target, characteristic, first);
    }

    @Override
    @SuppressWarnings("MissingPermission")
    public void stop() {
        ProximityLogger.getInstance().d("transport stop requested");
        started.set(false);
        stopScan();
        BluetoothGatt target = gatt;
        gatt = null;
        clientToServer = null;
        state = null;
        serverToClient = null;
        ident = null;
        l2capCharacteristic = null;
        assembler = new ChunkAssembler();
        clearSendQueue();
        try {
            if (target != null) {
                try {
                    target.disconnect();
                    target.close();
                } catch (SecurityException e) {
                    throw new ProximityException("BLUETOOTH_CONNECT permission is not granted", e);
                }
            }
        } finally {
            BluetoothSocket pending = connectingSocket;
            connectingSocket = null;
            if (pending != null) {
                closeQuietly(pending);
            }
            L2capWire active = wire;
            wire = null;
            if (active != null) {
                active.close();
            }
            l2capConnector.shutdown();
        }
    }


    @SuppressWarnings("MissingPermission")
    private void writeChunk(@NonNull BluetoothGatt target,
                            @NonNull BluetoothGattCharacteristic characteristic,
                            @NonNull byte[] chunk) {
        characteristic.setValue(chunk);
        characteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
        try {
            if (!target.writeCharacteristic(characteristic)) {
                abortSend(new ProximityException("the BLE stack rejected a request chunk"));
            }
        } catch (SecurityException e) {
            abortSend(new ProximityException("BLUETOOTH_CONNECT permission is not granted", e));
        } catch (RuntimeException e) {
            abortSend(wrap(e));
        }
    }

    private void drain(@NonNull BluetoothGatt target, int status) {
        boolean failed = status != BluetoothGatt.GATT_SUCCESS;
        byte[] next = null;
        synchronized (sendLock) {
            if (!sending) {
                return;
            }
            if (failed) {
                sendQueue.clear();
                sending = false;
            } else {
                next = sendQueue.poll();
                if (next == null) {
                    sending = false;
                }
            }
        }
        if (failed) {
            report(new ProximityException("a request chunk failed to send with status " + status));
            return;
        }
        if (next == null) {
            return;
        }
        BluetoothGattCharacteristic characteristic = clientToServer;
        if (target != gatt || characteristic == null) {
            abortSend(new ProximityException("the mdoc disconnected mid request"));
            return;
        }
        writeChunk(target, characteristic, next);
    }

    private void abortSend(@NonNull ProximityException error) {
        clearSendQueue();
        report(error);
    }

    private void clearSendQueue() {
        synchronized (sendLock) {
            sendQueue.clear();
            sending = false;
        }
    }

    @SuppressWarnings("MissingPermission")
    private void releaseGatt(@NonNull BluetoothGatt target) {
        if (gatt == target) {
            gatt = null;
            clientToServer = null;
            state = null;
            serverToClient = null;
            ident = null;
            assembler = new ChunkAssembler();
            clearSendQueue();
        }
        try {
            target.close();
        } catch (SecurityException ignoredWhileClosing) {
        }
    }

    @SuppressWarnings("MissingPermission")
    private void subscribe(@NonNull BluetoothGatt target,
                           @NonNull BluetoothGattCharacteristic characteristic) {
        if (!target.setCharacteristicNotification(characteristic, true)) {
            report(new ProximityException(
                    "the BLE stack refused to enable notifications on " + characteristic.getUuid()));
            return;
        }
        BluetoothGattDescriptor descriptor =
                characteristic.getDescriptor(BleUuids.CLIENT_CHARACTERISTIC_CONFIG);
        if (descriptor == null) {
            report(new ProximityException("a notify characteristic has no CCC descriptor"));
            return;
        }
        descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
        if (!target.writeDescriptor(descriptor)) {
            report(new ProximityException(
                    "the BLE stack rejected the CCC write on " + characteristic.getUuid()));
        }
    }

    @SuppressWarnings("MissingPermission")
    private void stopScan() {
        BluetoothLeScanner active = scanner;
        scanner = null;
        if (active != null) {
            try {
                active.stopScan(scanCallback);
            } catch (SecurityException ignoredWhileStopping) {
                return;
            }
        }
    }

    private void report(@NonNull ProximityException error) {
        Listener host = listener;
        if (host != null) {
            host.onError(error);
        }
    }

    @NonNull
    private static ProximityException wrap(@NonNull RuntimeException error) {
        if (error instanceof ProximityException) {
            return (ProximityException) error;
        }
        return new ProximityException("the BLE transport callback failed: " + error, error);
    }
}
