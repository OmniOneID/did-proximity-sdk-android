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

import org.omnione.did.proximity.sdk.core.engagement.BleMode;
import org.omnione.did.proximity.sdk.core.engagement.NfcOptions;
import org.omnione.did.proximity.sdk.core.logger.ProximityLogger;

import java.util.UUID;

public final class NfcHandoverResults {

    private NfcHandoverResults() {
    }

    @NonNull
    public static NfcHandoverResult of(@NonNull byte[] deviceEngagement,
                                       @NonNull BleMode mode,
                                       @NonNull UUID serviceUuid,
                                       @NonNull byte[] handoverSelect,
                                       @Nullable byte[] handoverRequest) {
        return NfcHandoverResult.of(deviceEngagement, mode, serviceUuid,
                handoverSelect, handoverRequest);
    }

    @NonNull
    public static NfcHandoverResult of(@NonNull byte[] deviceEngagement,
                                       @NonNull BleMode mode,
                                       @NonNull UUID serviceUuid,
                                       @NonNull byte[] handoverSelect,
                                       @Nullable byte[] handoverRequest,
                                       @Nullable Integer psm) {
        return NfcHandoverResult.of(deviceEngagement, mode, serviceUuid,
                handoverSelect, handoverRequest, psm);
    }

    @NonNull
    public static NfcHandoverResult nfc(@NonNull byte[] deviceEngagement,
                                        @NonNull NfcOptions nfcOptions,
                                        @NonNull byte[] handoverSelect,
                                        @Nullable byte[] handoverRequest,
                                        @Nullable ApduChannel channel) {
        return NfcHandoverResult.nfc(deviceEngagement, nfcOptions, handoverSelect,
                handoverRequest, channel);
    }

    public static void closeQuietly(@Nullable NfcHandoverResult result) {
        if (result == null || result.channel() == null) {
            return;
        }
        try {
            result.channel().close();
        } catch (RuntimeException e) {
            ProximityLogger.getInstance().d("handover channel close failed: " + e);
        }
    }
}
