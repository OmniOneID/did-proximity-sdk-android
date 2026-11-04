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

package org.omnione.did.proximity.sdk.core.engagement;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.upokecenter.cbor.CBORObject;
import com.upokecenter.cbor.CBORType;

import org.omnione.did.proximity.sdk.core.ProximityException;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class DeviceRetrievalMethod {

    private static final int TYPE_NFC = 1;
    private static final int TYPE_BLE = 2;
    private static final int TYPE_WIFI_AWARE = 3;
    private static final int VERSION = 1;

    private static final int OPTION_PERIPHERAL_SERVER_MODE = 0;
    private static final int OPTION_CENTRAL_CLIENT_MODE = 1;
    private static final int OPTION_PERIPHERAL_SERVER_UUID = 10;
    private static final int OPTION_CENTRAL_CLIENT_UUID = 11;
    private static final int OPTION_DEVICE_ADDRESS = 20;
    private static final int OPTION_L2CAP_PSM = 21;
    private static final int MIN_PSM = 1;
    private static final int MAX_PSM = 0xFFFF;

    private final Map<BleMode, UUID> uuids;
    private final WifiOptions wifi;
    private final NfcOptions nfc;
    private final Integer psm;

    private DeviceRetrievalMethod(@NonNull Map<BleMode, UUID> uuids, @Nullable Integer psm) {
        this.uuids = Collections.unmodifiableMap(new EnumMap<>(uuids));
        this.wifi = null;
        this.nfc = null;
        this.psm = psm;
    }

    private DeviceRetrievalMethod(@NonNull WifiOptions wifi) {
        this.uuids = Collections.emptyMap();
        this.wifi = wifi;
        this.nfc = null;
        this.psm = null;
    }

    private DeviceRetrievalMethod(@NonNull NfcOptions nfc) {
        this.uuids = Collections.emptyMap();
        this.wifi = null;
        this.nfc = nfc;
        this.psm = null;
    }

    @NonNull
    public static DeviceRetrievalMethod ble(@NonNull Map<BleMode, UUID> uuids) {
        if (uuids.isEmpty()) {
            throw new ProximityException("a BLE retrieval method needs at least one mode");
        }
        return new DeviceRetrievalMethod(uuids, null);
    }

    @NonNull
    public DeviceRetrievalMethod withPsm(@Nullable Integer psm) {
        if (psm == null) {
            return wifi != null || nfc != null ? this : new DeviceRetrievalMethod(uuids, null);
        }
        if (!uuids.containsKey(BleMode.PERIPHERAL_SERVER)) {
            throw new ProximityException(
                    "an L2CAP PSM needs the BLE peripheral server mode");
        }
        if (psm < MIN_PSM || psm > MAX_PSM) {
            throw new ProximityException("the L2CAP PSM is out of range: " + psm);
        }
        return new DeviceRetrievalMethod(uuids, psm);
    }

    @Nullable
    public Integer psm() {
        return psm;
    }

    @NonNull
    public static DeviceRetrievalMethod wifiAware(@NonNull WifiOptions options) {
        return new DeviceRetrievalMethod(options);
    }

    public boolean isWifiAware() {
        return wifi != null;
    }

    @NonNull
    public WifiOptions wifiOptions() {
        if (wifi == null) {
            throw new ProximityException("this retrieval method is not Wi-Fi Aware");
        }
        return wifi;
    }

    @NonNull
    public static DeviceRetrievalMethod nfc(@NonNull NfcOptions options) {
        return new DeviceRetrievalMethod(options);
    }

    public boolean isNfc() {
        return nfc != null;
    }

    @NonNull
    public NfcOptions nfcOptions() {
        if (nfc == null) {
            throw new ProximityException("this retrieval method is not NFC");
        }
        return nfc;
    }

    @NonNull
    public TransportMode transportMode() {
        if (nfc != null) {
            return TransportMode.NFC;
        }
        return wifi != null ? TransportMode.WIFI_AWARE : TransportMode.of(mode());
    }

    @NonNull
    public static DeviceRetrievalMethod blePeripheralServerMode(@NonNull UUID serviceUuid) {
        return ble(Collections.singletonMap(BleMode.PERIPHERAL_SERVER, serviceUuid));
    }

    @NonNull
    public static DeviceRetrievalMethod bleCentralClientMode(@NonNull UUID serviceUuid) {
        return ble(Collections.singletonMap(BleMode.CENTRAL_CLIENT, serviceUuid));
    }

    @NonNull
    public Set<BleMode> modes() {
        return uuids.keySet();
    }

    @NonNull
    public UUID serviceUuid(@NonNull BleMode mode) {
        UUID uuid = uuids.get(mode);
        if (uuid == null) {
            throw new ProximityException("this retrieval method does not carry " + mode);
        }
        return uuid;
    }

    @NonNull
    public BleMode mode() {
        if (uuids.size() != 1) {
            throw new ProximityException(
                    "this retrieval method carries " + uuids.size() + " modes, not one");
        }
        return uuids.keySet().iterator().next();
    }

    @NonNull
    public UUID serviceUuid() {
        return serviceUuid(mode());
    }

    @NonNull
    static List<DeviceRetrievalMethod> parse(@NonNull CBORObject method) {
        if (method.getType() != CBORType.Array || method.size() < 3) {
            throw new ProximityException("DeviceRetrievalMethod is malformed");
        }
        int type = asInt(method.get(0));
        if (type == TYPE_NFC) {
            return Collections.singletonList(nfc(NfcOptions.parse(method.get(2))));
        }
        if (type == TYPE_WIFI_AWARE) {
            return Collections.singletonList(wifiAware(WifiOptions.parse(method.get(2))));
        }
        if (type != TYPE_BLE) {
            throw new ProximityException("unsupported retrieval method type " + type);
        }
        CBORObject options = method.get(2);
        if (options.getType() != CBORType.Map) {
            throw new ProximityException("BleOptions is malformed");
        }
        Integer psm = peripheralPsm(options);

        List<DeviceRetrievalMethod> candidates = new ArrayList<>();
        if (advertises(options, OPTION_CENTRAL_CLIENT_MODE)) {
            candidates.add(bleCentralClientMode(uuidAt(options, OPTION_CENTRAL_CLIENT_UUID)));
        }
        if (advertises(options, OPTION_PERIPHERAL_SERVER_MODE)) {
            candidates.add(blePeripheralServerMode(uuidAt(options, OPTION_PERIPHERAL_SERVER_UUID))
                    .withPsm(psm));
        }
        if (candidates.isEmpty()) {
            throw new ProximityException("BleOptions advertises neither BLE GATT mode");
        }
        return candidates;
    }

    private static boolean advertises(@NonNull CBORObject options, int key) {
        CBORObject flag = options.get(key);
        return flag != null && flag.getType() == CBORType.Boolean && flag.AsBoolean();
    }

    @Nullable
    private static Integer peripheralPsm(@NonNull CBORObject options) {
        CBORObject address = options.get(OPTION_DEVICE_ADDRESS);
        CBORObject psm = options.get(OPTION_L2CAP_PSM);
        if (address == null && psm == null) {
            return null;
        }
        if (!advertises(options, OPTION_PERIPHERAL_SERVER_MODE)) {
            throw new ProximityException(
                    "BleOptions carries a peripheral only field without peripheral server mode");
        }
        if (address != null && address.getType() != CBORType.ByteString) {
            throw new ProximityException(
                    "the BLE device address must be a byte string, was " + address.getType());
        }
        if (psm != null
                && (psm.getType() != CBORType.Integer || !psm.CanValueFitInInt32()
                        || psm.AsInt32Value() < 0)) {
            throw new ProximityException("the L2CAP PSM must be an unsigned integer");
        }
        return psm == null ? null : psm.AsInt32Value();
    }

    @NonNull
    private static UUID uuidAt(@NonNull CBORObject options, int key) {
        CBORObject uuid = options.get(key);
        if (uuid == null || uuid.getType() != CBORType.ByteString) {
            throw new ProximityException("BleOptions is missing the service UUID (key " + key + ")");
        }
        return toUuid(uuid.GetByteString());
    }

    private static int asInt(@NonNull CBORObject value) {
        if (value.getType() != CBORType.Integer || !value.CanValueFitInInt32()) {
            throw new ProximityException("expected an integer, got " + value.getType());
        }
        return value.AsInt32Value();
    }

    @NonNull
    private static UUID toUuid(@NonNull byte[] bytes) {
        if (bytes.length != 16) {
            throw new ProximityException("a service UUID must be 16 bytes, was " + bytes.length);
        }
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        return new UUID(buffer.getLong(), buffer.getLong());
    }

    @NonNull
    public CBORObject toCbor() {
        if (nfc != null) {
            CBORObject method = CBORObject.NewArray();
            method.Add(TYPE_NFC);
            method.Add(VERSION);
            method.Add(nfc.toCbor());
            return method;
        }
        if (wifi != null) {
            CBORObject method = CBORObject.NewArray();
            method.Add(TYPE_WIFI_AWARE);
            method.Add(VERSION);
            method.Add(wifi.toCbor());
            return method;
        }
        boolean peripheral = uuids.containsKey(BleMode.PERIPHERAL_SERVER);
        boolean central = uuids.containsKey(BleMode.CENTRAL_CLIENT);

        CBORObject options = CBORObject.NewMap();
        options.Add(OPTION_PERIPHERAL_SERVER_MODE, peripheral);
        options.Add(OPTION_CENTRAL_CLIENT_MODE, central);
        if (peripheral) {
            options.Add(OPTION_PERIPHERAL_SERVER_UUID,
                    toBytes(uuids.get(BleMode.PERIPHERAL_SERVER)));
        }
        if (central) {
            options.Add(OPTION_CENTRAL_CLIENT_UUID, toBytes(uuids.get(BleMode.CENTRAL_CLIENT)));
        }
        if (peripheral && psm != null) {
            options.Add(OPTION_L2CAP_PSM, psm);
        }

        CBORObject method = CBORObject.NewArray();
        method.Add(TYPE_BLE);
        method.Add(VERSION);
        method.Add(options);
        return method;
    }

    @NonNull
    private static byte[] toBytes(@NonNull UUID uuid) {
        ByteBuffer buffer = ByteBuffer.allocate(16);
        buffer.putLong(uuid.getMostSignificantBits());
        buffer.putLong(uuid.getLeastSignificantBits());
        return buffer.array();
    }
}
