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

package org.omnione.did.proximity.sdk.core.message;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class ItemsRequest {

    private final String docType;
    private final Map<String, Map<String, Boolean>> requestedElements;
    private final byte[] readerAuth;

    ItemsRequest(@NonNull String docType,
                 @NonNull Map<String, Map<String, Boolean>> requestedElements,
                 @Nullable byte[] readerAuth) {
        this.docType = docType;
        this.requestedElements = copyAndWrap(requestedElements);
        this.readerAuth = readerAuth;
    }

    @NonNull
    public static ItemsRequest of(@NonNull String docType,
                                  @NonNull Map<String, Map<String, Boolean>> requestedElements) {
        return new ItemsRequest(docType, requestedElements, null);
    }

    @NonNull
    public String docType() {
        return docType;
    }

    @NonNull
    public Map<String, Map<String, Boolean>> requestedElements() {
        return requestedElements;
    }

    @Nullable
    public byte[] readerAuth() {
        return readerAuth == null ? null : readerAuth.clone();
    }

    @NonNull
    private static Map<String, Map<String, Boolean>> copyAndWrap(
            @NonNull Map<String, Map<String, Boolean>> requestedElements) {
        Map<String, Map<String, Boolean>> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Boolean>> entry : requestedElements.entrySet()) {
            copy.put(entry.getKey(), Collections.unmodifiableMap(new LinkedHashMap<>(entry.getValue())));
        }
        return Collections.unmodifiableMap(copy);
    }
}
