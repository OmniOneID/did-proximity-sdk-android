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

import java.util.UUID;

public final class BleUuids {

    public static final UUID STATE =
            UUID.fromString("00000001-a123-48ce-896b-4c76973373e6");
    public static final UUID CLIENT_TO_SERVER =
            UUID.fromString("00000002-a123-48ce-896b-4c76973373e6");
    public static final UUID SERVER_TO_CLIENT =
            UUID.fromString("00000003-a123-48ce-896b-4c76973373e6");
    public static final UUID READER_STATE =
            UUID.fromString("00000005-a123-48ce-896b-4c76973373e6");
    public static final UUID READER_CLIENT_TO_SERVER =
            UUID.fromString("00000006-a123-48ce-896b-4c76973373e6");
    public static final UUID READER_SERVER_TO_CLIENT =
            UUID.fromString("00000007-a123-48ce-896b-4c76973373e6");
    public static final UUID READER_IDENT =
            UUID.fromString("00000008-a123-48ce-896b-4c76973373e6");
    public static final UUID L2CAP =
            UUID.fromString("0000000a-a123-48ce-896b-4c76973373e6");
    public static final UUID READER_L2CAP =
            UUID.fromString("0000000b-a123-48ce-896b-4c76973373e6");

    public static final UUID CLIENT_CHARACTERISTIC_CONFIG =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    public static final byte STATE_START = 0x01;
    public static final byte STATE_END = 0x02;

    private BleUuids() {}
}
