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

package org.omnione.did.proximity.sdk.core.cbor;

import androidx.annotation.NonNull;

import com.upokecenter.cbor.CBORObject;
import com.upokecenter.cbor.CBORType;

import org.omnione.did.proximity.sdk.core.ProximityException;

public final class CborTags {

    public static final int ENCODED_CBOR = 24;

    private CborTags() {}

    @NonNull
    public static CBORObject tagged(@NonNull byte[] encoded) {
        return CBORObject.FromObject(encoded).WithTag(ENCODED_CBOR);
    }

    @NonNull
    public static byte[] untag(@NonNull CBORObject tagged) {
        if (!tagged.HasMostOuterTag(ENCODED_CBOR)) {
            throw new ProximityException("expected outer tag " + ENCODED_CBOR + ", got " + tagged.getMostOuterTag());
        }
        if (tagged.getType() != CBORType.ByteString) {
            throw new ProximityException("expected tagged byte string, got " + tagged.getType());
        }
        return tagged.GetByteString();
    }
}
