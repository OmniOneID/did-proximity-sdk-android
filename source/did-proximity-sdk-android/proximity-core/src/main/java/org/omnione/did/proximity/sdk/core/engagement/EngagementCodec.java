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

import org.omnione.did.proximity.sdk.core.ProximityException;

import java.util.Base64;

public final class EngagementCodec {

    private static final String SCHEME = "mdoc:";

    private EngagementCodec() {}

    @NonNull
    public static String encode(@NonNull byte[] deviceEngagement) {
        return SCHEME + Base64.getUrlEncoder().withoutPadding().encodeToString(deviceEngagement);
    }

    @NonNull
    public static byte[] decode(@NonNull String qr) {
        if (!qr.startsWith(SCHEME)) {
            throw new ProximityException("not an mdoc engagement string");
        }
        try {
            return Base64.getUrlDecoder().decode(qr.substring(SCHEME.length()));
        } catch (IllegalArgumentException e) {
            throw new ProximityException("engagement payload is not base64url", e);
        }
    }
}
