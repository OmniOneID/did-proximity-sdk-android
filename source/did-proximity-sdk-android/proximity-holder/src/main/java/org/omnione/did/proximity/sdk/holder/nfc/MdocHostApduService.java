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

package org.omnione.did.proximity.sdk.holder.nfc;

import android.nfc.cardemulation.HostApduService;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.omnione.did.proximity.sdk.core.engagement.BleMode;
import org.omnione.did.proximity.sdk.core.nfc.BleCarrier;
import org.omnione.did.proximity.sdk.core.nfc.HandoverResponder;
import org.omnione.did.proximity.sdk.core.nfc.NfcCarrier;
import org.omnione.did.proximity.sdk.holder.HolderSession;
import org.omnione.did.proximity.sdk.holder.NfcEngagementRegistry;

import java.util.Map;
import java.util.UUID;

public class MdocHostApduService extends HostApduService {

    private HandoverResponder cached;
    private final HceRouter router = new HceRouter(this::responder, () -> {
        HolderSession session = NfcEngagementRegistry.liveSession();
        return session == null ? null : NfcEngagementRegistry.nfcTransport(session);
    });

    @Nullable
    @Override
    public byte[] processCommandApdu(@NonNull byte[] commandApdu, @Nullable Bundle extras) {
        return router.processCommandApdu(commandApdu, this::sendResponseApdu);
    }

    @Override
    public void onDeactivated(int reason) {
        router.onDeactivated();
        cached = null;
    }

    @Nullable
    protected HandoverResponder responder() {
        if (cached != null) {
            return cached;
        }
        HolderSession session = NfcEngagementRegistry.liveSession();
        if (session == null) {
            return null;
        }
        Map<BleMode, UUID> uuids = NfcEngagementRegistry.serviceUuids(session);
        if (uuids.isEmpty()) {
            return null;
        }
        UUID shared = uuids.values().iterator().next();
        cached = HandoverResponder.staticHandover(
                NfcEngagementRegistry.nfcEngagementBytes(session),
                BleCarrier.of(uuids.keySet(), shared)
                        .withPsm(NfcEngagementRegistry.l2capPsm(session)),
                NfcCarrier.of(NfcEngagementRegistry.nfcOptions(session)),
                (handoverSelect, handoverRequest) ->
                        NfcEngagementRegistry.latch(session, handoverSelect, handoverRequest));
        return cached;
    }
}
