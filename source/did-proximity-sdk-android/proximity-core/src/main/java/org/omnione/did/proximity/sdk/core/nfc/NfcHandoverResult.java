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

import org.omnione.did.proximity.sdk.core.ProximityException;
import org.omnione.did.proximity.sdk.core.engagement.BleMode;
import org.omnione.did.proximity.sdk.core.engagement.DeviceEngagement;
import org.omnione.did.proximity.sdk.core.engagement.NfcOptions;
import org.omnione.did.proximity.sdk.core.engagement.SessionTranscripts;
import org.omnione.did.proximity.sdk.core.engagement.TransportMode;

import java.security.interfaces.ECPublicKey;
import java.util.UUID;

public final class NfcHandoverResult {

    private final byte[] deviceEngagement;
    private final ECPublicKey eDeviceKey;
    private final byte[] eDeviceKeyBytes;
    private final TransportMode selected;
    private final BleMode mode;
    private final UUID serviceUuid;
    private final NfcOptions nfcOptions;
    private final ApduChannel channel;
    private final byte[] handoverSelect;
    private final byte[] handoverRequest;
    private final Integer psm;

    private NfcHandoverResult(@NonNull byte[] deviceEngagement,
                              @NonNull ECPublicKey eDeviceKey,
                              @NonNull byte[] eDeviceKeyBytes,
                              @NonNull TransportMode selected,
                              @Nullable BleMode mode,
                              @Nullable UUID serviceUuid,
                              @Nullable NfcOptions nfcOptions,
                              @Nullable ApduChannel channel,
                              @NonNull byte[] handoverSelect,
                              @Nullable byte[] handoverRequest,
                              @Nullable Integer psm) {
        this.deviceEngagement = deviceEngagement;
        this.eDeviceKey = eDeviceKey;
        this.eDeviceKeyBytes = eDeviceKeyBytes;
        this.selected = selected;
        this.mode = mode;
        this.serviceUuid = serviceUuid;
        this.nfcOptions = nfcOptions;
        this.channel = channel;
        this.handoverSelect = handoverSelect;
        this.handoverRequest = handoverRequest;
        this.psm = psm;
    }

    @NonNull
    static NfcHandoverResult of(@NonNull byte[] deviceEngagement,
                                @NonNull BleMode mode,
                                @NonNull UUID serviceUuid,
                                @NonNull byte[] handoverSelect,
                                @Nullable byte[] handoverRequest) {
        return of(deviceEngagement, mode, serviceUuid, handoverSelect, handoverRequest, null);
    }

    @NonNull
    static NfcHandoverResult of(@NonNull byte[] deviceEngagement,
                                @NonNull BleMode mode,
                                @NonNull UUID serviceUuid,
                                @NonNull byte[] handoverSelect,
                                @Nullable byte[] handoverRequest,
                                @Nullable Integer psm) {
        DeviceEngagement parsed = parseEngagement(deviceEngagement);
        if (psm != null && mode != BleMode.PERIPHERAL_SERVER) {
            throw new ProximityException(
                    "an L2CAP PSM needs the BLE peripheral server mode");
        }
        return new NfcHandoverResult(deviceEngagement.clone(), parsed.eDeviceKey(),
                parsed.eDeviceKeyBytes(), TransportMode.of(mode), mode, serviceUuid, null, null,
                handoverSelect.clone(),
                handoverRequest == null ? null : handoverRequest.clone(), psm);
    }

    @NonNull
    static NfcHandoverResult nfc(@NonNull byte[] deviceEngagement,
                                 @NonNull NfcOptions nfcOptions,
                                 @NonNull byte[] handoverSelect,
                                 @Nullable byte[] handoverRequest,
                                 @Nullable ApduChannel channel) {
        DeviceEngagement parsed = parseEngagement(deviceEngagement);
        return new NfcHandoverResult(deviceEngagement.clone(), parsed.eDeviceKey(),
                parsed.eDeviceKeyBytes(), TransportMode.NFC, null, null, nfcOptions, channel,
                handoverSelect.clone(),
                handoverRequest == null ? null : handoverRequest.clone(), null);
    }

    @NonNull
    private static DeviceEngagement parseEngagement(@NonNull byte[] deviceEngagement) {
        DeviceEngagement parsed = DeviceEngagement.parseNfc(deviceEngagement);
        if (parsed.hasRetrievalMethods()) {
            throw new ProximityException(
                    "a DeviceEngagement carried over NFC must omit DeviceRetrievalMethods");
        }
        return parsed;
    }

    @NonNull
    public byte[] deviceEngagement() {
        return deviceEngagement.clone();
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
    public TransportMode selected() {
        return selected;
    }

    @Nullable
    public BleMode mode() {
        return mode;
    }

    @Nullable
    public UUID serviceUuid() {
        return serviceUuid;
    }

    @Nullable
    public NfcOptions nfcOptions() {
        return nfcOptions;
    }

    @Nullable
    public ApduChannel channel() {
        return channel;
    }

    @NonNull
    public byte[] handoverSelect() {
        return handoverSelect.clone();
    }

    @Nullable
    public Integer psm() {
        return psm;
    }

    @Nullable
    public byte[] handoverRequest() {
        return handoverRequest == null ? null : handoverRequest.clone();
    }

    public boolean negotiated() {
        return handoverRequest != null;
    }

    @NonNull
    public byte[] sessionTranscript(@NonNull byte[] eReaderKeyCoseKey) {
        return SessionTranscripts.forNfc(deviceEngagement, eReaderKeyCoseKey,
                handoverSelect, handoverRequest);
    }
}
