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
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.content.Context;
import android.os.ParcelUuid;

import androidx.annotation.NonNull;

import org.omnione.did.proximity.sdk.core.ProximityException;

import java.util.UUID;
import java.util.function.Consumer;

public final class BleAdvertiser {

    private final Context context;
    private BluetoothLeAdvertiser advertiser;
    private AdvertiseCallback callback;

    public BleAdvertiser(@NonNull Context context) {
        this.context = context.getApplicationContext();
    }

    @SuppressWarnings("MissingPermission")
    public synchronized void start(@NonNull UUID serviceUuid,
                                   @NonNull Consumer<ProximityException> onFailure) {
        BluetoothManager manager = context.getSystemService(BluetoothManager.class);
        BluetoothAdapter adapter = manager != null ? manager.getAdapter() : null;
        if (adapter == null || !isEnabled(adapter)) {
            throw new ProximityException("bluetooth is unavailable or disabled");
        }
        advertiser = adapter.getBluetoothLeAdvertiser();
        if (advertiser == null) {
            throw new ProximityException("BLE advertising is not supported on this device");
        }

        AdvertiseSettings settings = new AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
                .setConnectable(true)
                .setTimeout(0)
                .build();

        AdvertiseData data = new AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .addServiceUuid(new ParcelUuid(serviceUuid))
                .build();

        callback = new AdvertiseCallback() {
            @Override
            public void onStartFailure(int errorCode) {
                onFailure.accept(new ProximityException(
                        "BLE advertising failed with code " + errorCode));
            }
        };
        try {
            advertiser.startAdvertising(settings, data, callback);
        } catch (SecurityException e) {
            advertiser = null;
            callback = null;
            throw new ProximityException(
                    "BLUETOOTH_ADVERTISE permission is not granted", e);
        }
    }

    @SuppressWarnings("MissingPermission")
    public synchronized void stop() {
        if (advertiser != null && callback != null) {
            try {
                advertiser.stopAdvertising(callback);
            } catch (SecurityException e) {
                advertiser = null;
                callback = null;
                throw new ProximityException(
                        "BLUETOOTH_ADVERTISE permission is not granted", e);
            }
        }
        advertiser = null;
        callback = null;
    }

    @SuppressWarnings("MissingPermission")
    private static boolean isEnabled(@NonNull BluetoothAdapter adapter) {
        try {
            return adapter.isEnabled();
        } catch (SecurityException e) {
            throw new ProximityException("BLUETOOTH_CONNECT permission is not granted", e);
        }
    }
}
