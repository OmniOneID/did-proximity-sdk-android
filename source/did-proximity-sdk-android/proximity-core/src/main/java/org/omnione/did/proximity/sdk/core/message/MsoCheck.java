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

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class MsoCheck {

    public enum Outcome {
        PASSED,
        DIGEST_FAILED,
        VALIDITY_FAILED
    }

    private static final List<String> DIGEST_ALGORITHMS =
            Arrays.asList("SHA-256", "SHA-384", "SHA-512");

    @NonNull public final Outcome outcome;
    @Nullable public final String detail;

    private MsoCheck(@NonNull Outcome outcome, @Nullable String detail) {
        this.outcome = outcome;
        this.detail = detail;
    }

    public boolean passed() {
        return outcome == Outcome.PASSED;
    }

    @NonNull
    public static List<MsoCheck> verify(@NonNull byte[] deviceResponse, @NonNull Instant now) {
        List<MsoCheck> results = new ArrayList<>();
        CBORObject documents;
        try {
            documents = CBORObject.DecodeFromBytes(deviceResponse).get("documents");
        } catch (RuntimeException e) {
            return results;
        }
        if (documents == null || documents.getType() != CBORType.Array) {
            return results;
        }
        for (int i = 0; i < documents.size(); i++) {
            results.add(verifyDocument(documents.get(i), now));
        }
        return results;
    }

    @NonNull
    private static MsoCheck verifyDocument(@NonNull CBORObject document, @NonNull Instant now) {
        try {
            CBORObject issuerSigned = document.get("issuerSigned");
            CBORObject mso = issuerSigned == null ? null
                    : DeviceResponseParser.mso(issuerSigned.get("issuerAuth"));
            if (mso == null) {
                return new MsoCheck(Outcome.DIGEST_FAILED, "MSO cannot be decoded");
            }
            String failure = digestFailure(mso, issuerSigned.get("nameSpaces"));
            if (failure != null) {
                return new MsoCheck(Outcome.DIGEST_FAILED, failure);
            }
            return validity(mso.get("validityInfo"), now);
        } catch (RuntimeException e) {
            return new MsoCheck(Outcome.DIGEST_FAILED, String.valueOf(e));
        }
    }

    @Nullable
    private static String digestFailure(@NonNull CBORObject mso, @Nullable CBORObject nameSpaces) {
        CBORObject algorithm = mso.get("digestAlgorithm");
        if (algorithm == null || algorithm.getType() != CBORType.TextString
                || !DIGEST_ALGORITHMS.contains(algorithm.AsString())) {
            return "unsupported digestAlgorithm " + algorithm;
        }
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance(algorithm.AsString());
        } catch (NoSuchAlgorithmException e) {
            return "unsupported digestAlgorithm " + algorithm.AsString();
        }
        if (nameSpaces == null) {
            return null;
        }
        CBORObject valueDigests = mso.get("valueDigests");
        for (CBORObject namespace : nameSpaces.getKeys()) {
            CBORObject digests = valueDigests == null ? null : valueDigests.get(namespace);
            if (digests == null) {
                return "MSO has no digests for " + namespace.AsString();
            }
            CBORObject items = nameSpaces.get(namespace);
            for (int i = 0; i < items.size(); i++) {
                CBORObject itemBytes = items.get(i);
                CBORObject digestId = CBORObject.DecodeFromBytes(CborTags.untag(itemBytes))
                        .get("digestID");
                CBORObject expected = digestId == null ? null : digests.get(digestId);
                if (expected == null || expected.getType() != CBORType.ByteString) {
                    return "MSO has no digest " + digestId + " in " + namespace.AsString();
                }
                if (!MessageDigest.isEqual(expected.GetByteString(),
                        digest.digest(itemBytes.EncodeToBytes()))) {
                    return "digest mismatch for " + digestId + " in " + namespace.AsString();
                }
            }
        }
        return null;
    }

    @NonNull
    private static MsoCheck validity(@Nullable CBORObject validityInfo, @NonNull Instant now) {
        try {
            String failure = validityFailure(validityInfo, now);
            return new MsoCheck(failure == null ? Outcome.PASSED : Outcome.VALIDITY_FAILED, failure);
        } catch (RuntimeException e) {
            return new MsoCheck(Outcome.VALIDITY_FAILED, String.valueOf(e));
        }
    }

    @Nullable
    private static String validityFailure(@Nullable CBORObject validityInfo, @NonNull Instant now) {
        if (validityInfo == null || validityInfo.getType() != CBORType.Map) {
            return "MSO has no validityInfo";
        }
        Instant validFrom = instant(validityInfo.get("validFrom"));
        Instant validUntil = instant(validityInfo.get("validUntil"));
        if (validFrom == null || validUntil == null) {
            return "validFrom or validUntil is missing or malformed";
        }
        if (now.isBefore(validFrom)) {
            return "not yet valid, validFrom " + validFrom;
        }
        if (now.isAfter(validUntil)) {
            return "expired, validUntil " + validUntil;
        }
        return null;
    }

    @Nullable
    private static Instant instant(@Nullable CBORObject value) {
        String tdate = DeviceResponseParser.tdate(value);
        return tdate == null ? null : OffsetDateTime.parse(tdate).toInstant();
    }
}
