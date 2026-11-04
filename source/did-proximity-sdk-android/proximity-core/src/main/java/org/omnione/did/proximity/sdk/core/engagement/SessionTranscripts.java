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
import androidx.annotation.Nullable;

import com.upokecenter.cbor.CBORObject;

import org.omnione.did.proximity.sdk.core.cbor.CborTags;

public final class SessionTranscripts {

    private SessionTranscripts() {}

    @NonNull
    public static byte[] forQr(@NonNull byte[] deviceEngagement,
                               @NonNull byte[] eReaderKeyCoseKey) {
        CBORObject transcript = CBORObject.NewArray();
        transcript.Add(CborTags.tagged(deviceEngagement));
        transcript.Add(CborTags.tagged(eReaderKeyCoseKey));
        transcript.Add(CBORObject.Null);
        return transcript.EncodeToBytes();
    }

    @NonNull
    public static byte[] forNfc(@NonNull byte[] deviceEngagement,
                                @NonNull byte[] eReaderKeyCoseKey,
                                @NonNull byte[] handoverSelect,
                                @Nullable byte[] handoverRequest) {
        CBORObject handover = CBORObject.NewArray();
        handover.Add(CBORObject.FromObject(handoverSelect));
        handover.Add(handoverRequest == null
                ? CBORObject.Null
                : CBORObject.FromObject(handoverRequest));

        CBORObject transcript = CBORObject.NewArray();
        transcript.Add(CborTags.tagged(deviceEngagement));
        transcript.Add(CborTags.tagged(eReaderKeyCoseKey));
        transcript.Add(handover);
        return transcript.EncodeToBytes();
    }

    @NonNull
    public static byte[] taggedBytes(@NonNull byte[] sessionTranscript) {
        return CborTags.tagged(sessionTranscript).EncodeToBytes();
    }
}
