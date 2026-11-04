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

package org.omnione.did.proximity.sdk.holder;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Map;

public final class ProximityRequest {

    @NonNull public final byte[] deviceRequest;
    @NonNull public final String docType;
    @NonNull public final Map<String, Map<String, Boolean>> requestedElements;
    @NonNull public final byte[] sessionTranscript;
    @Nullable public final byte[] readerAuth;

    ProximityRequest(@NonNull byte[] deviceRequest,
                     @NonNull String docType,
                     @NonNull Map<String, Map<String, Boolean>> requestedElements,
                     @NonNull byte[] sessionTranscript,
                     @Nullable byte[] readerAuth) {
        this.deviceRequest = deviceRequest.clone();
        this.docType = docType;
        this.requestedElements = requestedElements;
        this.sessionTranscript = sessionTranscript.clone();
        this.readerAuth = readerAuth == null ? null : readerAuth.clone();
    }
}
