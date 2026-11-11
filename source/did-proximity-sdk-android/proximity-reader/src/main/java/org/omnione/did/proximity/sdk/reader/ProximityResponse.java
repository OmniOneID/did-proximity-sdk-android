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

package org.omnione.did.proximity.sdk.reader;

import androidx.annotation.NonNull;

import org.omnione.did.proximity.sdk.core.message.DeviceAuthResult;
import org.omnione.did.proximity.sdk.core.message.Document;
import org.omnione.did.proximity.sdk.core.message.MsoCheck;

import java.util.Collections;
import java.util.List;

public final class ProximityResponse {

    @NonNull public final List<Document> documents;
    public final int documentStatus;
    @NonNull public final byte[] deviceResponse;
    @NonNull public final byte[] sessionTranscript;
    @NonNull public final List<DeviceAuthResult> deviceAuth;
    @NonNull public final List<MsoCheck> msoChecks;

    ProximityResponse(@NonNull List<Document> documents,
                      int documentStatus,
                      @NonNull byte[] deviceResponse,
                      @NonNull byte[] sessionTranscript,
                      @NonNull List<DeviceAuthResult> deviceAuth,
                      @NonNull List<MsoCheck> msoChecks) {
        this.documents = Collections.unmodifiableList(documents);
        this.documentStatus = documentStatus;
        this.deviceResponse = deviceResponse.clone();
        this.sessionTranscript = sessionTranscript.clone();
        this.deviceAuth = Collections.unmodifiableList(deviceAuth);
        this.msoChecks = Collections.unmodifiableList(msoChecks);
    }

    public boolean deviceAuthVerified() {
        if (deviceAuth.size() != documents.size()) {
            return false;
        }
        for (DeviceAuthResult result : deviceAuth) {
            if (result.outcome != DeviceAuthResult.Outcome.VERIFIED) {
                return false;
            }
        }
        return true;
    }
}
