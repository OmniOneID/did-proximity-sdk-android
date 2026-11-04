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

public final class Document {

    @NonNull public final String docType;
    @NonNull public final Map<String, Map<String, Object>> nameSpaces;
    @NonNull public final byte[] issuerAuth;
    @Nullable public final byte[] deviceSigned;
    public final boolean hasX5Chain;
    @Nullable public final String validFrom;
    @Nullable public final String validUntil;

    Document(@NonNull String docType,
             @NonNull Map<String, Map<String, Object>> nameSpaces,
             @NonNull byte[] issuerAuth,
             @Nullable byte[] deviceSigned,
             boolean hasX5Chain,
             @Nullable String validFrom,
             @Nullable String validUntil) {
        this.docType = docType;
        this.nameSpaces = unmodifiable(nameSpaces);
        this.issuerAuth = issuerAuth.clone();
        this.deviceSigned = deviceSigned == null ? null : deviceSigned.clone();
        this.hasX5Chain = hasX5Chain;
        this.validFrom = validFrom;
        this.validUntil = validUntil;
    }

    @NonNull
    public Map<String, Object> flatClaims() {
        Map<String, Object> flat = new LinkedHashMap<>();
        for (Map<String, Object> elements : nameSpaces.values()) {
            flat.putAll(elements);
        }
        return Collections.unmodifiableMap(flat);
    }

    @NonNull
    private static Map<String, Map<String, Object>> unmodifiable(
            @NonNull Map<String, Map<String, Object>> source) {
        Map<String, Map<String, Object>> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Object>> entry : source.entrySet()) {
            copy.put(entry.getKey(),
                    Collections.unmodifiableMap(new LinkedHashMap<>(entry.getValue())));
        }
        return Collections.unmodifiableMap(copy);
    }
}
