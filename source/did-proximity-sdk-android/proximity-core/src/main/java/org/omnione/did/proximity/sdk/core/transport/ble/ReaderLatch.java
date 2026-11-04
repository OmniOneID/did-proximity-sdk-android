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
import androidx.annotation.Nullable;

final class ReaderLatch<D> {

    enum Outcome {
        LATCHED,
        ACCEPTED,
        REJECTED
    }

    private final Object lock = new Object();
    private final int defaultMtu;

    private D reader;
    private D pendingMtuDevice;
    private int pendingMtu;
    private int mtu;

    ReaderLatch(int defaultMtu) {
        this.defaultMtu = defaultMtu;
        this.mtu = defaultMtu;
    }

    @NonNull
    Outcome claim(@NonNull D device) {
        synchronized (lock) {
            if (reader != null) {
                return reader.equals(device) ? Outcome.ACCEPTED : Outcome.REJECTED;
            }
            reader = device;
            if (device.equals(pendingMtuDevice)) {
                mtu = pendingMtu;
            }
            pendingMtuDevice = null;
            return Outcome.LATCHED;
        }
    }

    boolean holds(@NonNull D device) {
        synchronized (lock) {
            return device.equals(reader);
        }
    }

    @Nullable
    D current() {
        synchronized (lock) {
            return reader;
        }
    }

    boolean release(@NonNull D device) {
        synchronized (lock) {
            if (!device.equals(reader)) {
                return false;
            }
            resetLocked();
            return true;
        }
    }

    void clear() {
        synchronized (lock) {
            resetLocked();
        }
    }

    void noteMtu(@NonNull D device, int value) {
        synchronized (lock) {
            if (device.equals(reader)) {
                mtu = value;
            } else if (reader == null) {
                pendingMtuDevice = device;
                pendingMtu = value;
            }
        }
    }

    int mtu() {
        synchronized (lock) {
            return mtu;
        }
    }

    private void resetLocked() {
        reader = null;
        pendingMtuDevice = null;
        pendingMtu = 0;
        mtu = defaultMtu;
    }
}
