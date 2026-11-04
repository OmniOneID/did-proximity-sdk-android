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

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothSocket;
import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.omnione.did.proximity.sdk.core.ProximityException;
import org.omnione.did.proximity.sdk.core.logger.ProximityLogger;
import org.omnione.did.proximity.sdk.core.transport.ChunkAssembler;
import org.omnione.did.proximity.sdk.core.transport.Transport;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class GattServerTransport implements Transport {

    private static final int DEFAULT_MTU = 23;
    private static final byte[] NO_VALUE = new byte[0];

    private enum Path {
        GATT,
        L2CAP
    }

    private final Context context;
    private final BleAdvertiser advertiser;
    private final GattProfile profile;
    private final UUID serviceUuid;
    private final byte[] identValue;
    private final BluetoothL2capServer l2cap;
    private volatile ChunkAssembler assembler = new ChunkAssembler();
    private final Object sendLock = new Object();
    private final Queue<byte[]> sendQueue = new ArrayDeque<>();
    private final AtomicBoolean started = new AtomicBoolean();
    private final ReaderLatch<BluetoothDevice> latch = new ReaderLatch<>(DEFAULT_MTU);
    private final AtomicReference<Path> path = new AtomicReference<>();
    private volatile L2capWire wire;

    private boolean sending;
    private BluetoothDevice sendTarget;
    private volatile BluetoothGattServer server;
    private volatile BluetoothGattCharacteristic serverToClient;
    private volatile Listener listener;

    public GattServerTransport(@NonNull Context context, @NonNull UUID serviceUuid) {
        this(context, serviceUuid, GattProfile.MDOC, null);
    }

    public GattServerTransport(@NonNull Context context,
                               @NonNull UUID serviceUuid,
                               @NonNull GattProfile profile,
                               @Nullable byte[] identValue) {
        this(context, serviceUuid, profile, identValue, null);
    }

    public GattServerTransport(@NonNull Context context,
                               @NonNull UUID serviceUuid,
                               @NonNull GattProfile profile,
                               @Nullable byte[] identValue,
                               @Nullable BluetoothL2capServer l2cap) {
        this.context = context.getApplicationContext();
        this.serviceUuid = serviceUuid;
        this.advertiser = new BleAdvertiser(context);
        this.profile = profile;
        this.identValue = identValue == null ? null : identValue.clone();
        this.l2cap = l2cap;
        if (profile.ident() != null && identValue == null) {
            throw new ProximityException(
                    "a GATT profile with an ident characteristic requires an ident value");
        }
    }

    @Override
    @SuppressWarnings("MissingPermission")
    public void start(@NonNull Listener listener) {
        if (!started.compareAndSet(false, true)) {
            throw new ProximityException("the transport is already started");
        }
        try {
            open(listener);
        } catch (RuntimeException e) {
            BluetoothGattServer opened = server;
            if (opened != null) {
                rollback(opened);
            }
            started.set(false);
            throw e;
        }
        if (l2cap != null) {
            Thread accept = new Thread(this::acceptLoop, "proximity-l2cap-accept");
            accept.setDaemon(true);
            accept.start();
        }
    }

    private void acceptLoop() {
        BluetoothL2capServer server = l2cap;
        if (server == null) {
            return;
        }
        while (started.get() && path.get() == null) {
            BluetoothSocket socket;
            try {
                socket = server.accept();
            } catch (IOException e) {
                if (started.get() && path.get() == null) {
                    ProximityLogger.getInstance().d("l2cap accept ended: " + e);
                }
                return;
            }
            if (!adoptL2cap(socket)) {
                closeQuietly(socket);
            }
        }
    }

    @SuppressWarnings("MissingPermission")
    private boolean adoptL2cap(@NonNull BluetoothSocket socket) {
        BluetoothL2capServer server = l2cap;
        if (server == null || !started.get()) {
            return false;
        }
        BluetoothDevice device = socket.getRemoteDevice();
        if (latch.claim(device) != ReaderLatch.Outcome.LATCHED) {
            ProximityLogger.getInstance().d("l2cap connection from " + device
                    + " ignored, path=" + path.get());
            return false;
        }
        if (!path.compareAndSet(null, Path.L2CAP)) {
            latch.release(device);
            return false;
        }
        L2capWire opened;
        try {
            opened = new L2capWire(socket.getInputStream(), socket.getOutputStream(), socket,
                    L2capWire.DEFAULT_CLOSE_DELAY_MS);
        } catch (IOException e) {
            latch.release(device);
            path.set(null);
            ProximityLogger.getInstance().d("l2cap socket streams unavailable: " + e);
            return false;
        }
        wire = opened;
        server.close();
        advertiser.stop();
        ProximityLogger.getInstance().d("reader latched " + device
                + " transport=l2cap psm=" + server.psm());
        opened.serve(wireListener);
        Listener host = listener;
        if (host != null) {
            host.onConnected();
        }
        return true;
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
            latch.clear();
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

    @SuppressWarnings("MissingPermission")
    private void open(@NonNull Listener listener) {
        this.listener = listener;

        BluetoothManager manager = context.getSystemService(BluetoothManager.class);
        if (manager == null) {
            throw new ProximityException("BluetoothManager is unavailable");
        }
        BluetoothGattServer opened;
        try {
            opened = manager.openGattServer(context, callback);
        } catch (SecurityException e) {
            throw new ProximityException("BLUETOOTH_CONNECT permission is not granted", e);
        }
        if (opened == null) {
            throw new ProximityException("failed to open GATT server");
        }
        server = opened;

        BluetoothGattService service = new BluetoothGattService(
                serviceUuid, BluetoothGattService.SERVICE_TYPE_PRIMARY);
        service.addCharacteristic(notifyAndWrite(profile.state()));
        service.addCharacteristic(writeOnly(profile.clientToServer()));
        serverToClient = notifyOnly(profile.serverToClient());
        service.addCharacteristic(serverToClient);
        UUID identUuid = profile.ident();
        if (identUuid != null) {
            service.addCharacteristic(readOnly(identUuid));
        }
        UUID l2capUuid = profile.l2cap();
        if (l2capUuid != null && l2cap != null) {
            service.addCharacteristic(readOnly(l2capUuid));
        }

        try {
            if (!opened.addService(service)) {
                throw new ProximityException("failed to register the GATT service");
            }
        } catch (SecurityException e) {
            throw new ProximityException("BLUETOOTH_CONNECT permission is not granted", e);
        }
    }

    @Override
    public void send(@NonNull byte[] message) {
        L2capWire active = wire;
        if (active != null) {
            ProximityLogger.getInstance().d("send " + message.length + " bytes over l2cap");
            active.send(message);
            return;
        }
        BluetoothGattServer target = server;
        BluetoothDevice peer = latch.current();
        if (target == null || peer == null) {
            throw new ProximityException("no reader is connected");
        }
        List<byte[]> chunks = ChunkAssembler.split(message,
                ChunkAssembler.maxChunkSize(latch.mtu()));
        ProximityLogger.getInstance().d("send " + message.length + " bytes as "
                + chunks.size() + " chunks, mtu=" + latch.mtu());
        byte[] first;
        synchronized (sendLock) {
            sendQueue.addAll(chunks);
            if (sending) {
                return;
            }
            sending = true;
            sendTarget = peer;
            first = sendQueue.poll();
        }
        notifyChunk(target, peer, first);
    }

    @Override
    @SuppressWarnings("MissingPermission")
    public void stop() {
        ProximityLogger.getInstance().d("transport stop requested");
        started.set(false);
        if (l2cap != null) {
            l2cap.close();
        }
        L2capWire active = wire;
        wire = null;
        if (active != null) {
            active.close();
        }
        BluetoothGattServer target = server;
        server = null;
        latch.clear();
        resetConnectionState();
        try {
            advertiser.stop();
        } finally {
            if (target != null) {
                try {
                    target.close();
                } catch (SecurityException e) {
                    throw new ProximityException(
                            "BLUETOOTH_CONNECT permission is not granted", e);
                }
            }
        }
    }

    @SuppressWarnings("MissingPermission")
    private void rollback(@NonNull BluetoothGattServer opened) {
        server = null;
        serverToClient = null;
        try {
            opened.close();
        } catch (RuntimeException ignoredWhileRollingBack) {
            report(wrap(ignoredWhileRollingBack));
        }
    }

    @SuppressWarnings("MissingPermission")
    private boolean latchReader(@NonNull BluetoothDevice device) {
        BluetoothGattServer target = server;
        if (target == null) {
            return false;
        }
        ReaderLatch.Outcome outcome = latch.claim(device);
        if (outcome == ReaderLatch.Outcome.REJECTED) {
            target.cancelConnection(device);
            return false;
        }
        if (outcome == ReaderLatch.Outcome.LATCHED) {
            if (!path.compareAndSet(null, Path.GATT)) {
                latch.release(device);
                return false;
            }
            ProximityLogger.getInstance().d("reader latched " + device
                    + " transport=gatt mtu=" + latch.mtu());
            if (l2cap != null) {
                l2cap.close();
            }
            advertiser.stop();
            Listener host = listener;
            if (host != null) {
                host.onConnected();
            }
        }
        return true;
    }

    private void releaseReader(@NonNull BluetoothDevice device) {
        if (!latch.release(device)) {
            ProximityLogger.getInstance().d("release ignored for unlatched " + device);
            return;
        }
        ProximityLogger.getInstance().d("reader released " + device);
        resetConnectionState();
        Listener host = listener;
        if (host != null) {
            host.onDisconnected();
        }
    }

    private void resetConnectionState() {
        assembler = new ChunkAssembler();
        discardQueuedChunks();
    }

    @SuppressWarnings("MissingPermission")
    private void notifyChunk(@NonNull BluetoothGattServer target,
                             @NonNull BluetoothDevice peer,
                             @NonNull byte[] chunk) {
        BluetoothGattCharacteristic characteristic = serverToClient;
        if (characteristic == null) {
            abortSend(new ProximityException("the server to client characteristic is unavailable"));
            return;
        }
        characteristic.setValue(chunk);
        try {
            if (!target.notifyCharacteristicChanged(peer, characteristic, false)) {
                abortSend(new ProximityException("the BLE stack rejected a response chunk"));
                return;
            }
            ProximityLogger.getInstance().d("notified chunk of " + chunk.length + " bytes");
        } catch (RuntimeException e) {
            abortSend(wrap(e));
        }
    }

    private void abortSend(@NonNull ProximityException error) {
        discardQueuedChunks();
        report(error);
    }

    private void discardQueuedChunks() {
        synchronized (sendLock) {
            sendQueue.clear();
            sending = false;
            sendTarget = null;
        }
    }

    private void drain(@NonNull BluetoothDevice device, int status) {
        boolean failed = status != BluetoothGatt.GATT_SUCCESS;
        byte[] next = null;
        synchronized (sendLock) {
            if (!sending || !device.equals(sendTarget)) {
                return;
            }
            if (failed) {
                sendQueue.clear();
                sending = false;
                sendTarget = null;
            } else {
                next = sendQueue.poll();
                if (next == null) {
                    sending = false;
                    sendTarget = null;
                }
            }
        }
        if (failed) {
            report(new ProximityException(
                    "a response chunk failed to send with status " + status));
            return;
        }
        if (next == null) {
            return;
        }
        BluetoothGattServer target = server;
        BluetoothDevice peer = latch.current();
        if (target == null || peer == null || !peer.equals(device)) {
            abortSend(new ProximityException("the reader disconnected mid response"));
            return;
        }
        notifyChunk(target, peer, next);
    }

    @NonNull
    private static ProximityException wrap(@NonNull RuntimeException error) {
        if (error instanceof ProximityException) {
            return (ProximityException) error;
        }
        return new ProximityException("the BLE transport callback failed: " + error, error);
    }

    private void report(@NonNull ProximityException error) {
        Listener target = listener;
        if (target == null) {
            return;
        }
        try {
            target.onError(error);
        } catch (RuntimeException ignoredWhileReporting) {
            return;
        }
    }

    @SuppressWarnings("MissingPermission")
    private void respond(@NonNull BluetoothDevice device, int requestId, int offset) {
        BluetoothGattServer target = server;
        if (target == null) {
            return;
        }
        try {
            target.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null);
        } catch (SecurityException e) {
            throw new ProximityException("BLUETOOTH_CONNECT permission is not granted", e);
        }
    }

    private void handleWrite(@NonNull BluetoothDevice device,
                             int requestId,
                             @NonNull BluetoothGattCharacteristic characteristic,
                             boolean responseNeeded,
                             int offset,
                             byte[] value) {
        if (responseNeeded) {
            respond(device, requestId, offset);
        }
        byte[] payload = value != null ? value : NO_VALUE;
        BleWriteSignal signal = BleWriteSignal.of(profile, characteristic.getUuid(), payload);
        ProximityLogger.getInstance().d("write " + signal + " on "
                + characteristic.getUuid() + ", " + payload.length + " bytes");
        if (path.get() == Path.L2CAP) {
            ProximityLogger.getInstance().d("gatt write ignored while l2cap is active");
            return;
        }
        switch (signal) {
            case SESSION_START:
                latchReader(device);
                return;
            case SESSION_END:
                releaseReader(device);
                return;
            case DATA_CHUNK: {
                if (!latchReader(device)) {
                    return;
                }
                byte[] message = assembler.append(payload);
                if (message != null) {
                    Listener host = listener;
                    if (host != null) {
                        host.onMessage(message);
                    }
                }
                return;
            }
            default:
        }
    }

    private final BluetoothGattServerCallback callback = new BluetoothGattServerCallback() {
        @Override
        public void onConnectionStateChange(BluetoothDevice device, int status, int newState) {
            try {
                if (newState != BluetoothGatt.STATE_DISCONNECTED) {
                    return;
                }
                if (path.get() == Path.L2CAP) {
                    ProximityLogger.getInstance().d("gatt disconnect of " + device
                            + " ignored while l2cap is active");
                    return;
                }
                releaseReader(device);
            } catch (RuntimeException e) {
                report(wrap(e));
            }
        }

        @Override
        public void onServiceAdded(int status, BluetoothGattService service) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                report(new ProximityException(
                        "the GATT service was not registered, status " + status));
                return;
            }
            if (server == null || !started.get()) {
                return;
            }
            ProximityLogger.getInstance().d("GATT service registered "
                    + service.getUuid() + ", starting advertise");
            try {
                advertiser.start(service.getUuid(), GattServerTransport.this::report);
                if (!started.get()) {
                    advertiser.stop();
                }
            } catch (RuntimeException e) {
                report(wrap(e));
            }
        }

        @Override
        public void onMtuChanged(BluetoothDevice device, int newMtu) {
            latch.noteMtu(device, newMtu);
        }

        @Override
        public void onNotificationSent(BluetoothDevice device, int status) {
            try {
                drain(device, status);
            } catch (RuntimeException e) {
                abortSend(wrap(e));
            }
        }

        @Override
        public void onCharacteristicWriteRequest(BluetoothDevice device,
                                                 int requestId,
                                                 BluetoothGattCharacteristic characteristic,
                                                 boolean preparedWrite,
                                                 boolean responseNeeded,
                                                 int offset,
                                                 byte[] value) {
            try {
                handleWrite(device, requestId, characteristic, responseNeeded, offset, value);
            } catch (RuntimeException e) {
                report(wrap(e));
            }
        }

        @Override
        @SuppressWarnings("MissingPermission")
        public void onCharacteristicReadRequest(BluetoothDevice device, int requestId, int offset,
                                                BluetoothGattCharacteristic characteristic) {
            BluetoothGattServer target = server;
            if (target == null) {
                return;
            }
            byte[] value = readableValue(characteristic.getUuid());
            try {
                if (value == null || offset > value.length) {
                    target.sendResponse(device, requestId,
                            BluetoothGatt.GATT_FAILURE, offset, null);
                    return;
                }
                target.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset,
                        Arrays.copyOfRange(value, offset, value.length));
            } catch (SecurityException e) {
                report(new ProximityException(
                        "BLUETOOTH_CONNECT permission is not granted", e));
            } catch (RuntimeException e) {
                report(wrap(e));
            }
        }

        @Override
        public void onDescriptorWriteRequest(BluetoothDevice device, int requestId,
                                             BluetoothGattDescriptor descriptor,
                                             boolean preparedWrite, boolean responseNeeded,
                                             int offset, byte[] value) {
            if (!responseNeeded) {
                return;
            }
            try {
                respond(device, requestId, offset);
            } catch (RuntimeException e) {
                report(wrap(e));
            }
        }
    };

    @Nullable
    private byte[] readableValue(@NonNull UUID uuid) {
        UUID identUuid = profile.ident();
        if (identUuid != null && identUuid.equals(uuid)) {
            return identValue;
        }
        UUID l2capUuid = profile.l2cap();
        BluetoothL2capServer server = l2cap;
        if (l2capUuid != null && server != null && l2capUuid.equals(uuid)) {
            return L2capFraming.encodePsm(server.psm());
        }
        return null;
    }

    @NonNull
    private static BluetoothGattCharacteristic notifyOnly(@NonNull UUID uuid) {
        BluetoothGattCharacteristic characteristic = new BluetoothGattCharacteristic(
                uuid,
                BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_READ);
        characteristic.addDescriptor(new BluetoothGattDescriptor(
                BleUuids.CLIENT_CHARACTERISTIC_CONFIG,
                BluetoothGattDescriptor.PERMISSION_READ
                        | BluetoothGattDescriptor.PERMISSION_WRITE));
        return characteristic;
    }

    @NonNull
    private static BluetoothGattCharacteristic writeOnly(@NonNull UUID uuid) {
        return new BluetoothGattCharacteristic(
                uuid,
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                BluetoothGattCharacteristic.PERMISSION_WRITE);
    }

    @NonNull
    private static BluetoothGattCharacteristic notifyAndWrite(@NonNull UUID uuid) {
        BluetoothGattCharacteristic characteristic = new BluetoothGattCharacteristic(
                uuid,
                BluetoothGattCharacteristic.PROPERTY_NOTIFY
                        | BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                BluetoothGattCharacteristic.PERMISSION_WRITE);
        characteristic.addDescriptor(new BluetoothGattDescriptor(
                BleUuids.CLIENT_CHARACTERISTIC_CONFIG,
                BluetoothGattDescriptor.PERMISSION_READ
                        | BluetoothGattDescriptor.PERMISSION_WRITE));
        return characteristic;
    }

    @NonNull
    private static BluetoothGattCharacteristic readOnly(@NonNull UUID uuid) {
        return new BluetoothGattCharacteristic(
                uuid,
                BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ);
    }
}
