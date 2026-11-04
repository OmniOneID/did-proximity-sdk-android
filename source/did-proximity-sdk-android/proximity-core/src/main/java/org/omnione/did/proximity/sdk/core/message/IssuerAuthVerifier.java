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

import com.upokecenter.cbor.CBORObject;
import com.upokecenter.cbor.CBORType;

import org.omnione.did.proximity.sdk.core.cbor.CborTags;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.interfaces.ECPublicKey;

public final class IssuerAuthVerifier {

    private static final int COSE_PROTECTED = 0;
    private static final int COSE_UNPROTECTED = 1;
    private static final int COSE_PAYLOAD = 2;
    private static final int COSE_SIGNATURE = 3;
    private static final int COSE_LABEL_KID = 4;
    private static final String KEY_DOC_TYPE = "docType";

    private IssuerAuthVerifier() {}

    @Nullable
    public static String kid(@NonNull byte[] issuerAuth) {
        try {
            CBORObject sign1 = sign1(issuerAuth);
            if (sign1 == null) return null;
            String kid = protectedKid(sign1.get(COSE_PROTECTED));
            return kid != null ? kid : kidOf(sign1.get(COSE_UNPROTECTED));
        } catch (RuntimeException e) {
            return null;
        }
    }

    public static boolean verify(@NonNull byte[] issuerAuth, @NonNull ECPublicKey key) {
        try {
            CBORObject sign1 = sign1(issuerAuth);
            if (sign1 == null) return false;
            return DeviceAuthVerifier.sign1Verifies(
                    DeviceAuthVerifier.signatureAlgorithm(DeviceAuthVerifier.alg(sign1)),
                    DeviceAuthVerifier.byteString(sign1.get(COSE_PROTECTED), "protected header"),
                    DeviceAuthVerifier.byteString(sign1.get(COSE_PAYLOAD), "issuerAuth payload"),
                    DeviceAuthVerifier.byteString(sign1.get(COSE_SIGNATURE), "issuerAuth signature"),
                    key);
        } catch (GeneralSecurityException | RuntimeException e) {
            return false;
        }
    }

    @Nullable
    public static String msoDocType(@NonNull byte[] issuerAuth) {
        try {
            CBORObject sign1 = sign1(issuerAuth);
            if (sign1 == null) return null;
            CBORObject mso = CBORObject.DecodeFromBytes(sign1.get(COSE_PAYLOAD).GetByteString());
            if (mso.HasMostOuterTag(CborTags.ENCODED_CBOR)) {
                mso = CBORObject.DecodeFromBytes(CborTags.untag(mso));
            }
            CBORObject docType = mso.getType() == CBORType.Map ? mso.get(KEY_DOC_TYPE) : null;
            return docType != null && docType.getType() == CBORType.TextString
                    ? docType.AsString() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    @Nullable
    private static CBORObject sign1(@NonNull byte[] issuerAuth) {
        CBORObject sign1 = CBORObject.DecodeFromBytes(issuerAuth);
        if (sign1.getType() != CBORType.Array || sign1.size() != 4) return null;
        return sign1;
    }

    @Nullable
    private static String protectedKid(@Nullable CBORObject header) {
        try {
            byte[] encoded = header.GetByteString();
            return encoded.length == 0 ? null : kidOf(CBORObject.DecodeFromBytes(encoded));
        } catch (RuntimeException e) {
            return null;
        }
    }

    @Nullable
    private static String kidOf(@Nullable CBORObject header) {
        if (header == null || header.getType() != CBORType.Map) return null;
        CBORObject kid = header.get(COSE_LABEL_KID);
        if (kid == null) return null;
        if (kid.getType() == CBORType.TextString) return kid.AsString();
        if (kid.getType() == CBORType.ByteString) {
            return new String(kid.GetByteString(), StandardCharsets.UTF_8);
        }
        return null;
    }
}
