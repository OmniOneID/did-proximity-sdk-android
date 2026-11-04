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

package org.omnione.did.proximity.sdk.core.nfc;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.upokecenter.cbor.CBORObject;
import com.upokecenter.cbor.CBORType;

import org.omnione.did.proximity.sdk.core.ProximityException;
import org.omnione.did.proximity.sdk.core.engagement.BleMode;
import org.omnione.did.proximity.sdk.core.engagement.ModePreference;
import org.omnione.did.proximity.sdk.core.logger.ProximityLogger;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

public final class BleCarrier {

    public enum Role {
        HANDOVER_SELECT,
        HANDOVER_REQUEST
    }

    public static final String RECORD_TYPE = "application/vnd.bluetooth.le.oob";

    static final int AD_SERVICE_UUID_128 = 0x07;
    static final int AD_SERVICE_DATA_16 = 0x16;
    static final int AD_DEVICE_ADDRESS = 0x1B;
    static final int AD_LE_ROLE = 0x1C;

    static final int SERVICE_DATA_UUID_MDOC = 0xFF01;
    private static final int SERVICE_DATA_KEY_PSM = 0;
    private static final int MIN_PSM = 1;
    private static final int MAX_PSM = 0xFFFF;

    static final int LE_ROLE_PERIPHERAL_ONLY = 0x00;
    static final int LE_ROLE_CENTRAL_ONLY = 0x01;
    static final int LE_ROLE_BOTH_PERIPHERAL_PREFERRED = 0x02;
    static final int LE_ROLE_BOTH_CENTRAL_PREFERRED = 0x03;

    private static final int UUID_BYTES = 16;

    private final Set<BleMode> modes;
    private final UUID serviceUuid;
    private final Integer psm;

    private BleCarrier(@NonNull Set<BleMode> modes, @Nullable UUID serviceUuid,
                       @Nullable Integer psm) {
        this.modes = Collections.unmodifiableSet(modes);
        this.serviceUuid = serviceUuid;
        this.psm = psm;
    }

    @NonNull
    public static BleCarrier of(@NonNull Set<BleMode> modes, @Nullable UUID serviceUuid) {
        if (modes.isEmpty()) {
            throw new ProximityException("a BLE carrier needs at least one mode");
        }
        return new BleCarrier(EnumSet.copyOf(modes), serviceUuid, null);
    }

    @NonNull
    public BleCarrier withPsm(@Nullable Integer psm) {
        if (psm == null) {
            return new BleCarrier(EnumSet.copyOf(modes), serviceUuid, null);
        }
        if (!modes.contains(BleMode.PERIPHERAL_SERVER)) {
            throw new ProximityException(
                    "an L2CAP PSM needs the BLE peripheral server mode");
        }
        if (psm < MIN_PSM || psm > MAX_PSM) {
            throw new ProximityException("the L2CAP PSM is out of range: " + psm);
        }
        return new BleCarrier(EnumSet.copyOf(modes), serviceUuid, psm);
    }

    @Nullable
    public Integer psm() {
        return psm;
    }

    @NonNull
    public Set<BleMode> modes() {
        return modes;
    }

    @Nullable
    public UUID serviceUuid() {
        return serviceUuid;
    }

    public boolean supports(@NonNull BleMode mode) {
        return modes.contains(mode);
    }

    @NonNull
    public byte[] toHandoverSelect() {
        return encode(mdocLeRole());
    }

    @NonNull
    public byte[] toHandoverRequest(@NonNull ModePreference preference) {
        return encode(readerLeRole(preference));
    }

    int mdocLeRole() {
        boolean central = modes.contains(BleMode.CENTRAL_CLIENT);
        boolean peripheral = modes.contains(BleMode.PERIPHERAL_SERVER);
        if (central && peripheral) {
            return LE_ROLE_BOTH_CENTRAL_PREFERRED;
        }
        return central ? LE_ROLE_CENTRAL_ONLY : LE_ROLE_PERIPHERAL_ONLY;
    }

    int readerLeRole(@NonNull ModePreference preference) {
        boolean central = modes.contains(BleMode.CENTRAL_CLIENT);
        boolean peripheral = modes.contains(BleMode.PERIPHERAL_SERVER);
        if (central && peripheral) {
            return preference.preferred(modes) == BleMode.CENTRAL_CLIENT
                    ? LE_ROLE_BOTH_PERIPHERAL_PREFERRED
                    : LE_ROLE_BOTH_CENTRAL_PREFERRED;
        }
        return central ? LE_ROLE_PERIPHERAL_ONLY : LE_ROLE_CENTRAL_ONLY;
    }

    @NonNull
    private byte[] encode(int leRole) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(2);
        out.write(AD_LE_ROLE);
        out.write(leRole);
        if (serviceUuid != null) {
            byte[] reversed = uuidToLittleEndian(serviceUuid);
            out.write(reversed.length + 1);
            out.write(AD_SERVICE_UUID_128);
            out.write(reversed, 0, reversed.length);
        }
        if (psm != null) {
            byte[] serviceData = CBORObject.NewMap()
                    .Add(SERVICE_DATA_KEY_PSM, psm)
                    .EncodeToBytes();
            out.write(serviceData.length + 3);
            out.write(AD_SERVICE_DATA_16);
            out.write(SERVICE_DATA_UUID_MDOC & 0xFF);
            out.write((SERVICE_DATA_UUID_MDOC >>> 8) & 0xFF);
            out.write(serviceData, 0, serviceData.length);
        }
        return out.toByteArray();
    }

    @NonNull
    public static BleCarrier parse(@NonNull byte[] oobData, @NonNull Role role) {
        Integer leRole = null;
        UUID uuid = null;
        Integer psm = null;

        int pos = 0;
        while (pos < oobData.length) {
            int length = oobData[pos] & 0xFF;
            if (length == 0) {
                break;
            }
            if (pos + 1 + length > oobData.length) {
                throw new ProximityException("truncated AD structure in the BLE carrier record");
            }
            int type = oobData[pos + 1] & 0xFF;
            int valueOffset = pos + 2;
            int valueLength = length - 1;

            if (type == AD_LE_ROLE && valueLength == 1) {
                leRole = oobData[valueOffset] & 0xFF;
            } else if (type == AD_SERVICE_UUID_128 && valueLength == UUID_BYTES) {
                uuid = uuidFromLittleEndian(oobData, valueOffset);
            } else if (type == AD_SERVICE_DATA_16 && valueLength >= 2
                    && serviceDataUuid(oobData, valueOffset) == SERVICE_DATA_UUID_MDOC) {
                psm = parsePsm(oobData, valueOffset + 2, valueLength - 2);
            }

            pos += length + 1;
        }

        if (leRole == null) {
            throw new ProximityException("the BLE carrier record carries no LE Role (AD 0x1C)");
        }
        Set<BleMode> modes = modesFor(leRole, role);
        if (psm != null && (role != Role.HANDOVER_SELECT
                || !modes.contains(BleMode.PERIPHERAL_SERVER))) {
            ProximityLogger.getInstance().d("nfc: ignoring an L2CAP PSM outside the mdoc"
                    + " peripheral server mode");
            psm = null;
        }
        return new BleCarrier(modes, uuid, psm);
    }

    private static int serviceDataUuid(@NonNull byte[] source, int offset) {
        return (source[offset] & 0xFF) | ((source[offset + 1] & 0xFF) << 8);
    }

    @Nullable
    private static Integer parsePsm(@NonNull byte[] source, int offset, int length) {
        try {
            CBORObject map = CBORObject.DecodeFromBytes(
                    Arrays.copyOfRange(source, offset, offset + length));
            if (map.getType() != CBORType.Map) {
                throw new ProximityException("MdocBleServiceData is not a map");
            }
            CBORObject value = map.get(SERVICE_DATA_KEY_PSM);
            if (value == null || value.getType() != CBORType.Integer
                    || !value.CanValueFitInInt32()) {
                throw new ProximityException("MdocBleServiceData carries no integer PSM");
            }
            int psm = value.AsInt32Value();
            if (psm < MIN_PSM || psm > MAX_PSM) {
                throw new ProximityException("the L2CAP PSM is out of range: " + psm);
            }
            return psm;
        } catch (RuntimeException e) {
            ProximityLogger.getInstance().d("nfc: ignoring malformed BLE service data: " + e);
            return null;
        }
    }

    @NonNull
    private static Set<BleMode> modesFor(int leRole, @NonNull Role role) {
        switch (leRole) {
            case LE_ROLE_BOTH_PERIPHERAL_PREFERRED:
            case LE_ROLE_BOTH_CENTRAL_PREFERRED:
                return EnumSet.of(BleMode.CENTRAL_CLIENT, BleMode.PERIPHERAL_SERVER);
            case LE_ROLE_CENTRAL_ONLY:
                return EnumSet.of(role == Role.HANDOVER_SELECT
                        ? BleMode.CENTRAL_CLIENT
                        : BleMode.PERIPHERAL_SERVER);
            case LE_ROLE_PERIPHERAL_ONLY:
                return EnumSet.of(role == Role.HANDOVER_SELECT
                        ? BleMode.PERIPHERAL_SERVER
                        : BleMode.CENTRAL_CLIENT);
            default:
                throw new ProximityException("unsupported LE Role value " + leRole);
        }
    }

    @NonNull
    static byte[] uuidToLittleEndian(@NonNull UUID uuid) {
        return ByteBuffer.allocate(UUID_BYTES).order(ByteOrder.LITTLE_ENDIAN)
                .putLong(uuid.getLeastSignificantBits())
                .putLong(uuid.getMostSignificantBits())
                .array();
    }

    @NonNull
    static UUID uuidFromLittleEndian(@NonNull byte[] source, int offset) {
        ByteBuffer buffer = ByteBuffer.wrap(source, offset, UUID_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        long least = buffer.getLong();
        long most = buffer.getLong();
        return new UUID(most, least);
    }
}
