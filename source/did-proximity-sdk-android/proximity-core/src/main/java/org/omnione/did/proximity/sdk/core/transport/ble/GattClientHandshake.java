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

final class GattClientHandshake {

    enum Action {
        CONNECT,
        DISCOVER_SERVICES,
        READ_IDENT,
        READ_PSM,
        CONNECT_L2CAP,
        SUBSCRIBE_STATE,
        SUBSCRIBE_SERVER_TO_CLIENT,
        REQUEST_MTU,
        WRITE_START,
        RECONNECT,
        ABORT,
        NONE
    }

    private enum Stage {
        SCANNING,
        CONNECTING,
        DISCOVERING,
        READING_IDENT,
        READING_PSM,
        CONNECTING_L2CAP,
        SUBSCRIBING_STATE,
        SUBSCRIBING_SERVER_TO_CLIENT,
        NEGOTIATING_MTU,
        STARTING,
        STARTED
    }

    private final int defaultMtu;
    private final boolean verifiesIdent;
    private final Object lock = new Object();

    private Stage stage = Stage.SCANNING;
    private int mtu;
    private boolean l2capOffered;
    private boolean l2capAttempted;
    private boolean gattConnected;

    GattClientHandshake(int defaultMtu) {
        this(defaultMtu, false);
    }

    GattClientHandshake(int defaultMtu, boolean verifiesIdent) {
        this.defaultMtu = defaultMtu;
        this.verifiesIdent = verifiesIdent;
        this.mtu = defaultMtu;
    }

    @NonNull
    Action onDeviceFound() {
        synchronized (lock) {
            if (stage != Stage.SCANNING) {
                return Action.NONE;
            }
            stage = Stage.CONNECTING;
            return Action.CONNECT;
        }
    }

    @NonNull
    Action onDeviceFoundWithPsm() {
        synchronized (lock) {
            if (stage != Stage.SCANNING) {
                return Action.NONE;
            }
            if (l2capAttempted) {
                stage = Stage.CONNECTING;
                return Action.CONNECT;
            }
            l2capAttempted = true;
            stage = Stage.CONNECTING_L2CAP;
            return Action.CONNECT_L2CAP;
        }
    }

    @NonNull
    Action onConnected() {
        synchronized (lock) {
            if (stage != Stage.CONNECTING) {
                return Action.NONE;
            }
            gattConnected = true;
            stage = Stage.DISCOVERING;
            return Action.DISCOVER_SERVICES;
        }
    }

    @NonNull
    Action onServicesDiscovered() {
        return onServicesDiscovered(false);
    }

    @NonNull
    Action onServicesDiscovered(boolean hasL2capCharacteristic) {
        synchronized (lock) {
            if (stage != Stage.DISCOVERING) {
                return Action.NONE;
            }
            l2capOffered = hasL2capCharacteristic && !l2capAttempted;
            if (verifiesIdent) {
                stage = Stage.READING_IDENT;
                return Action.READ_IDENT;
            }
            return afterIdent();
        }
    }

    @NonNull
    Action onIdentRead() {
        synchronized (lock) {
            if (stage != Stage.READING_IDENT) {
                return Action.NONE;
            }
            return afterIdent();
        }
    }

    @NonNull
    private Action afterIdent() {
        if (l2capOffered) {
            stage = Stage.READING_PSM;
            return Action.READ_PSM;
        }
        stage = Stage.SUBSCRIBING_STATE;
        return Action.SUBSCRIBE_STATE;
    }

    @NonNull
    Action onPsmRead(@Nullable Integer psm) {
        synchronized (lock) {
            if (stage != Stage.READING_PSM) {
                return Action.NONE;
            }
            l2capOffered = false;
            if (psm == null) {
                stage = Stage.SUBSCRIBING_STATE;
                return Action.SUBSCRIBE_STATE;
            }
            l2capAttempted = true;
            stage = Stage.CONNECTING_L2CAP;
            return Action.CONNECT_L2CAP;
        }
    }

    @NonNull
    Action onL2capConnected() {
        synchronized (lock) {
            if (stage != Stage.CONNECTING_L2CAP) {
                return Action.NONE;
            }
            stage = Stage.STARTED;
            return Action.NONE;
        }
    }

    @NonNull
    Action onL2capFailed() {
        synchronized (lock) {
            if (stage != Stage.CONNECTING_L2CAP) {
                return Action.NONE;
            }
            if (gattConnected) {
                stage = Stage.SUBSCRIBING_STATE;
                return Action.SUBSCRIBE_STATE;
            }
            stage = Stage.CONNECTING;
            return Action.CONNECT;
        }
    }

    @NonNull
    Action onDescriptorWritten() {
        synchronized (lock) {
            if (stage == Stage.SUBSCRIBING_STATE) {
                stage = Stage.SUBSCRIBING_SERVER_TO_CLIENT;
                return Action.SUBSCRIBE_SERVER_TO_CLIENT;
            }
            if (stage == Stage.SUBSCRIBING_SERVER_TO_CLIENT) {
                stage = Stage.NEGOTIATING_MTU;
                return Action.REQUEST_MTU;
            }
            return Action.NONE;
        }
    }

    @NonNull
    Action onMtuChanged(int negotiated) {
        synchronized (lock) {
            if (stage != Stage.NEGOTIATING_MTU) {
                return Action.NONE;
            }
            if (negotiated > defaultMtu) {
                mtu = negotiated;
            }
            stage = Stage.STARTING;
            return Action.WRITE_START;
        }
    }

    void onStartWritten() {
        synchronized (lock) {
            if (stage == Stage.STARTING) {
                stage = Stage.STARTED;
            }
        }
    }

    @NonNull
    Action onConnectionLost() {
        synchronized (lock) {
            if (stage == Stage.SCANNING) {
                return Action.NONE;
            }
            boolean wasStarted = (stage == Stage.STARTED);
            stage = Stage.SCANNING;
            mtu = defaultMtu;
            gattConnected = false;
            l2capOffered = false;
            return wasStarted ? Action.ABORT : Action.RECONNECT;
        }
    }

    boolean isStarted() {
        synchronized (lock) {
            return stage == Stage.STARTED;
        }
    }

    int mtu() {
        synchronized (lock) {
            return mtu;
        }
    }
}
