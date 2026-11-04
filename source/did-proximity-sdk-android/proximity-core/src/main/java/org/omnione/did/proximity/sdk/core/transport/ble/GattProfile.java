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

import java.util.UUID;

public final class GattProfile {

    public static final GattProfile MDOC = new GattProfile(
            BleUuids.STATE,
            BleUuids.CLIENT_TO_SERVER,
            BleUuids.SERVER_TO_CLIENT,
            null,
            BleUuids.L2CAP);

    public static final GattProfile MDOC_READER = new GattProfile(
            BleUuids.READER_STATE,
            BleUuids.READER_CLIENT_TO_SERVER,
            BleUuids.READER_SERVER_TO_CLIENT,
            BleUuids.READER_IDENT,
            BleUuids.READER_L2CAP);

    private final UUID state;
    private final UUID clientToServer;
    private final UUID serverToClient;
    private final UUID ident;
    private final UUID l2cap;

    private GattProfile(@NonNull UUID state,
                        @NonNull UUID clientToServer,
                        @NonNull UUID serverToClient,
                        @Nullable UUID ident,
                        @Nullable UUID l2cap) {
        this.state = state;
        this.clientToServer = clientToServer;
        this.serverToClient = serverToClient;
        this.ident = ident;
        this.l2cap = l2cap;
    }

    @NonNull
    public UUID state() {
        return state;
    }

    @NonNull
    public UUID clientToServer() {
        return clientToServer;
    }

    @NonNull
    public UUID serverToClient() {
        return serverToClient;
    }

    @Nullable
    public UUID ident() {
        return ident;
    }

    @Nullable
    public UUID l2cap() {
        return l2cap;
    }
}
