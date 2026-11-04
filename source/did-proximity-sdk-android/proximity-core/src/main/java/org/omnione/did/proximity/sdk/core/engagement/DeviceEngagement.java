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

import com.upokecenter.cbor.CBORObject;
import com.upokecenter.cbor.CBORType;

import org.omnione.did.proximity.sdk.core.ProximityException;
import org.omnione.did.proximity.sdk.core.cbor.CborTags;
import org.omnione.did.proximity.sdk.core.cbor.CoseKeys;

import java.security.interfaces.ECPublicKey;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class DeviceEngagement {

    private static final String VERSION_1_0 = "1.0";
    private static final int CIPHER_SUITE = 1;

    private static final int KEY_VERSION = 0;
    private static final int KEY_SECURITY = 1;
    private static final int KEY_RETRIEVAL_METHODS = 2;

    private final ECPublicKey eDeviceKey;
    private final byte[] eDeviceKeyBytes;
    private final List<DeviceRetrievalMethod> retrievalMethods;

    private DeviceEngagement(@NonNull ECPublicKey eDeviceKey,
                             @NonNull byte[] eDeviceKeyBytes,
                             @NonNull List<DeviceRetrievalMethod> retrievalMethods) {
        this.eDeviceKey = eDeviceKey;
        this.eDeviceKeyBytes = eDeviceKeyBytes;
        this.retrievalMethods = Collections.unmodifiableList(new ArrayList<>(retrievalMethods));
    }

    @NonNull
    public static DeviceEngagement forQr(@NonNull ECPublicKey eDeviceKey,
                                         @NonNull UUID serviceUuid) {
        return forQr(eDeviceKey, serviceUuid, BleMode.PERIPHERAL_SERVER);
    }

    @NonNull
    public static DeviceEngagement forQr(@NonNull ECPublicKey eDeviceKey,
                                         @NonNull UUID serviceUuid,
                                         @NonNull BleMode mode) {
        return forQr(eDeviceKey, Collections.singletonMap(mode, serviceUuid));
    }

    @NonNull
    public static DeviceEngagement forQr(@NonNull ECPublicKey eDeviceKey,
                                         @NonNull Map<BleMode, UUID> uuids) {
        return forQr(eDeviceKey, Collections.singletonList(DeviceRetrievalMethod.ble(uuids)));
    }

    @NonNull
    public static DeviceEngagement forQr(@NonNull ECPublicKey eDeviceKey,
                                         @NonNull List<DeviceRetrievalMethod> methods) {
        if (methods.isEmpty()) {
            throw new ProximityException("a QR DeviceEngagement needs at least one retrieval method");
        }
        return new DeviceEngagement(eDeviceKey, taggedKeyBytes(eDeviceKey), methods);
    }

    @NonNull
    public static DeviceEngagement forNfc(@NonNull ECPublicKey eDeviceKey) {
        return new DeviceEngagement(eDeviceKey, taggedKeyBytes(eDeviceKey),
                Collections.emptyList());
    }

    @NonNull
    private static byte[] taggedKeyBytes(@NonNull ECPublicKey eDeviceKey) {
        return CborTags.tagged(CoseKeys.toCoseKey(eDeviceKey).EncodeToBytes()).EncodeToBytes();
    }

    public boolean hasRetrievalMethods() {
        return !retrievalMethods.isEmpty();
    }

    @NonNull
    public List<DeviceRetrievalMethod> retrievalMethods() {
        return retrievalMethods;
    }

    @NonNull
    public DeviceRetrievalMethod retrievalMethod() {
        if (retrievalMethods.size() != 1) {
            throw new ProximityException("this DeviceEngagement carries "
                    + retrievalMethods.size() + " retrieval methods, not one");
        }
        return retrievalMethods.get(0);
    }

    @NonNull
    public UUID serviceUuid() {
        return required().serviceUuid();
    }

    @NonNull
    public ECPublicKey eDeviceKey() {
        return eDeviceKey;
    }

    @NonNull
    public byte[] eDeviceKeyBytes() {
        return eDeviceKeyBytes.clone();
    }

    @NonNull
    public BleMode mode() {
        return required().mode();
    }

    @NonNull
    private DeviceRetrievalMethod required() {
        if (retrievalMethods.isEmpty()) {
            throw new ProximityException("this DeviceEngagement carries no device retrieval method;"
                    + " the carrier comes from the NFC handover records");
        }
        return retrievalMethod();
    }

    @NonNull
    public static DeviceEngagement parse(@NonNull byte[] encoded) {
        return parse(encoded, ModePreference.CENTRAL_CLIENT_FIRST);
    }

    @NonNull
    public static DeviceEngagement parseNfc(@NonNull byte[] encoded) {
        CBORObject engagement = decode(encoded);
        CBORObject taggedKey = securityKey(engagement);
        return new DeviceEngagement(CoseKeys.toPublicKey(CborTags.untag(taggedKey)),
                taggedKey.EncodeToBytes(), Collections.emptyList());
    }

    @NonNull
    public static DeviceEngagement parse(@NonNull byte[] encoded,
                                         @NonNull ModePreference preference) {
        return parse(encoded, TransportPreference.from(preference));
    }

    @NonNull
    public static DeviceEngagement parse(@NonNull byte[] encoded,
                                         @NonNull TransportPreference preference) {
        CBORObject engagement = decode(encoded);
        CBORObject taggedKey = securityKey(engagement);
        ECPublicKey key = CoseKeys.toPublicKey(CborTags.untag(taggedKey));

        CBORObject methods = engagement.get(KEY_RETRIEVAL_METHODS);
        if (methods == null || methods.getType() != CBORType.Array || methods.size() < 1) {
            throw new ProximityException("DeviceEngagement carries no device retrieval method");
        }
        return new DeviceEngagement(key, taggedKey.EncodeToBytes(),
                Collections.singletonList(select(methods, preference)));
    }

    @NonNull
    private static CBORObject decode(@NonNull byte[] encoded) {
        CBORObject engagement;
        try {
            engagement = CBORObject.DecodeFromBytes(encoded);
        } catch (RuntimeException e) {
            throw new ProximityException("DeviceEngagement is not valid CBOR", e);
        }
        if (engagement.getType() != CBORType.Map) {
            throw new ProximityException("DeviceEngagement is not a map");
        }
        return engagement;
    }

    @NonNull
    private static CBORObject securityKey(@NonNull CBORObject engagement) {
        CBORObject security = engagement.get(KEY_SECURITY);
        if (security == null || security.getType() != CBORType.Array || security.size() != 2) {
            throw new ProximityException("DeviceEngagement Security is malformed");
        }
        CBORObject cipherSuite = security.get(0);
        if (cipherSuite.getType() != CBORType.Integer
                || !cipherSuite.CanValueFitInInt32()
                || cipherSuite.AsInt32Value() != CIPHER_SUITE) {
            throw new ProximityException("unsupported cipher suite, expected " + CIPHER_SUITE);
        }
        return security.get(1);
    }

    @NonNull
    private static DeviceRetrievalMethod select(@NonNull CBORObject methods,
                                                @NonNull TransportPreference preference) {
        List<DeviceRetrievalMethod> candidates = new ArrayList<>();
        ProximityException lastRejection = null;
        for (int i = 0; i < methods.size(); i++) {
            try {
                candidates.addAll(DeviceRetrievalMethod.parse(methods.get(i)));
            } catch (ProximityException e) {
                lastRejection = e;
            }
        }
        for (TransportMode mode : preference.order()) {
            for (DeviceRetrievalMethod candidate : candidates) {
                if (candidate.transportMode() == mode) {
                    return candidate;
                }
            }
        }
        throw new UnsupportedTransportException(
                "no supported device retrieval method in DeviceEngagement", lastRejection);
    }

    @NonNull
    public byte[] encode() {
        CBORObject security = CBORObject.NewArray();
        security.Add(CIPHER_SUITE);
        security.Add(CBORObject.DecodeFromBytes(eDeviceKeyBytes));

        CBORObject engagement = CBORObject.NewMap();
        engagement.Add(KEY_VERSION, VERSION_1_0);
        engagement.Add(KEY_SECURITY, security);
        if (!retrievalMethods.isEmpty()) {
            CBORObject methods = CBORObject.NewArray();
            for (DeviceRetrievalMethod method : retrievalMethods) {
                methods.Add(method.toCbor());
            }
            engagement.Add(KEY_RETRIEVAL_METHODS, methods);
        }
        return engagement.EncodeToBytes();
    }
}
