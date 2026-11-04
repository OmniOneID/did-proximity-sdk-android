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
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

import org.omnione.did.proximity.sdk.core.ProximityException;
import org.omnione.did.proximity.sdk.core.logger.ProximityLogger;

import java.io.IOException;

public final class BluetoothL2capServer implements L2capServer {

    private final BluetoothServerSocket socket;
    private final int psm;

    @RequiresApi(Build.VERSION_CODES.Q)
    private BluetoothL2capServer(@NonNull BluetoothServerSocket socket) {
        this.socket = socket;
        this.psm = L2capFraming.requirePsm(socket.getPsm());
    }

    @NonNull
    @RequiresApi(Build.VERSION_CODES.Q)
    @SuppressWarnings("MissingPermission")
    public static BluetoothL2capServer open(@NonNull Context context) {
        BluetoothManager manager = context.getApplicationContext()
                .getSystemService(BluetoothManager.class);
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        if (adapter == null) {
            throw new ProximityException("this device has no Bluetooth adapter");
        }
        try {
            BluetoothL2capServer server =
                    new BluetoothL2capServer(adapter.listenUsingInsecureL2capChannel());
            ProximityLogger.getInstance().i("l2cap server listening psm=" + server.psm);
            return server;
        } catch (SecurityException e) {
            throw new ProximityException("BLUETOOTH_CONNECT permission is not granted", e);
        } catch (IOException e) {
            throw new ProximityException("the L2CAP server socket could not be opened", e);
        }
    }

    @Override
    public int psm() {
        return psm;
    }

    @NonNull
    public BluetoothSocket accept() throws IOException {
        return socket.accept();
    }

    @Override
    public void close() {
        try {
            socket.close();
        } catch (IOException e) {
            ProximityLogger.getInstance().d("l2cap server close failed: " + e);
        }
    }
}
