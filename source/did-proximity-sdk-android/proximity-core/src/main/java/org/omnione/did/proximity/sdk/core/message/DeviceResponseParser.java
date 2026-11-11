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

import org.omnione.did.proximity.sdk.core.ProximityException;
import org.omnione.did.proximity.sdk.core.cbor.CborTags;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class DeviceResponseParser {

    private static final int COSE_LABEL_X5CHAIN = 33;
    private static final int COSE_SIGN1_PAYLOAD = 2;
    private static final int MAX_ELEMENT_VALUE_DEPTH = 32;

    private static final String KEY_VERSION = "version";
    private static final String KEY_DOCUMENTS = "documents";
    private static final String KEY_STATUS = "status";
    private static final String KEY_DOC_TYPE = "docType";
    private static final String KEY_ISSUER_SIGNED = "issuerSigned";
    private static final String KEY_DEVICE_SIGNED = "deviceSigned";
    private static final String KEY_ISSUER_AUTH = "issuerAuth";
    private static final String KEY_NAME_SPACES = "nameSpaces";
    private static final String KEY_ELEMENT_IDENTIFIER = "elementIdentifier";
    private static final String KEY_ELEMENT_VALUE = "elementValue";
    private static final String KEY_VALIDITY_INFO = "validityInfo";
    private static final String KEY_VALID_FROM = "validFrom";
    private static final String KEY_VALID_UNTIL = "validUntil";

    private DeviceResponseParser() {}

    @NonNull
    public static DeviceResponse parse(@NonNull byte[] encoded) {
        CBORObject response = decode(encoded);
        if (response.getType() != CBORType.Map) {
            throw new ProximityException("DeviceResponse is not a map");
        }
        String version = text(response.get(KEY_VERSION), KEY_VERSION);
        CBORObject status = response.get(KEY_STATUS);
        if (status == null || status.getType() != CBORType.Integer
                || !status.CanValueFitInInt32()) {
            throw new ProximityException("DeviceResponse status is missing or malformed");
        }

        List<Document> documents = new ArrayList<>();
        CBORObject encodedDocuments = response.get(KEY_DOCUMENTS);
        if (encodedDocuments != null) {
            if (encodedDocuments.getType() != CBORType.Array) {
                throw new ProximityException("DeviceResponse documents is not an array");
            }
            for (int i = 0; i < encodedDocuments.size(); i++) {
                documents.add(parseDocument(encodedDocuments.get(i)));
            }
        }
        return new DeviceResponse(version, documents, status.AsInt32Value());
    }

    @NonNull
    private static Document parseDocument(@NonNull CBORObject document) {
        if (document.getType() != CBORType.Map) {
            throw new ProximityException("Document is not a map");
        }
        String docType = text(document.get(KEY_DOC_TYPE), KEY_DOC_TYPE);

        CBORObject issuerSigned = document.get(KEY_ISSUER_SIGNED);
        if (issuerSigned == null || issuerSigned.getType() != CBORType.Map) {
            throw new ProximityException("Document is missing issuerSigned");
        }
        CBORObject issuerAuth = issuerSigned.get(KEY_ISSUER_AUTH);
        if (issuerAuth == null) {
            throw new ProximityException("issuerSigned is missing issuerAuth");
        }

        CBORObject deviceSigned = document.get(KEY_DEVICE_SIGNED);
        String[] validity = validity(issuerAuth);
        return new Document(
                docType,
                parseNameSpaces(issuerSigned.get(KEY_NAME_SPACES)),
                issuerAuth.EncodeToBytes(),
                deviceSigned == null ? null : deviceSigned.EncodeToBytes(),
                carriesX5Chain(issuerAuth),
                validity[0],
                validity[1]);
    }

    @NonNull
    private static String[] validity(@NonNull CBORObject issuerAuth) {
        CBORObject mso = mso(issuerAuth);
        CBORObject validityInfo = mso == null ? null : mso.get(KEY_VALIDITY_INFO);
        if (validityInfo == null || validityInfo.getType() != CBORType.Map) {
            return new String[2];
        }
        return new String[]{
                tdate(validityInfo.get(KEY_VALID_FROM)),
                tdate(validityInfo.get(KEY_VALID_UNTIL))};
    }

    @Nullable
    static CBORObject mso(@Nullable CBORObject issuerAuth) {
        if (issuerAuth == null || issuerAuth.getType() != CBORType.Array
                || issuerAuth.size() <= COSE_SIGN1_PAYLOAD) {
            return null;
        }
        CBORObject payload = issuerAuth.get(COSE_SIGN1_PAYLOAD);
        if (payload.getType() != CBORType.ByteString) {
            return null;
        }
        try {
            CBORObject mso = CBORObject.DecodeFromBytes(payload.GetByteString());
            if (mso.HasMostOuterTag(CborTags.ENCODED_CBOR)) {
                mso = CBORObject.DecodeFromBytes(CborTags.untag(mso));
            }
            return mso.getType() == CBORType.Map ? mso : null;
        } catch (RuntimeException ignoredMalformedMso) {
            return null;
        }
    }

    @Nullable
    static String tdate(@Nullable CBORObject value) {
        if (value == null) {
            return null;
        }
        if (value.isTagged()) {
            value = value.Untag();
        }
        if (value.getType() == CBORType.TextString) {
            return value.AsString();
        }
        if (value.getType() == CBORType.Integer) {
            try {
                return Instant.ofEpochSecond(value.AsInt64Value()).toString();
            } catch (RuntimeException ignoredOutOfRangeEpoch) {
                return null;
            }
        }
        return null;
    }

    @NonNull
    private static Map<String, Map<String, Object>> parseNameSpaces(
            @Nullable CBORObject nameSpaces) {
        Map<String, Map<String, Object>> parsed = new LinkedHashMap<>();
        if (nameSpaces == null) {
            return parsed;
        }
        if (nameSpaces.getType() != CBORType.Map) {
            throw new ProximityException("IssuerNameSpaces is not a map");
        }
        for (CBORObject namespace : nameSpaces.getKeys()) {
            CBORObject items = nameSpaces.get(namespace);
            if (items.getType() != CBORType.Array) {
                throw new ProximityException("IssuerNameSpaces entry is not an array");
            }
            Map<String, Object> elements = new LinkedHashMap<>();
            for (int i = 0; i < items.size(); i++) {
                CBORObject item = CBORObject.DecodeFromBytes(CborTags.untag(items.get(i)));
                if (item.getType() != CBORType.Map) {
                    throw new ProximityException("IssuerSignedItem is not a map");
                }
                elements.put(
                        text(item.get(KEY_ELEMENT_IDENTIFIER), KEY_ELEMENT_IDENTIFIER),
                        toJava(item.get(KEY_ELEMENT_VALUE), 0));
            }
            parsed.put(text(namespace, "IssuerNameSpaces key"), elements);
        }
        return parsed;
    }

    private static boolean carriesX5Chain(@NonNull CBORObject issuerAuth) {
        if (issuerAuth.getType() != CBORType.Array || issuerAuth.size() < 2) {
            return false;
        }
        if (headerHasX5Chain(issuerAuth.get(1))) {
            return true;
        }
        CBORObject protectedHeader = issuerAuth.get(0);
        if (protectedHeader.getType() != CBORType.ByteString) {
            return false;
        }
        try {
            return headerHasX5Chain(CBORObject.DecodeFromBytes(protectedHeader.GetByteString()));
        } catch (RuntimeException ignoredMalformedProtectedHeader) {
            return false;
        }
    }

    private static boolean headerHasX5Chain(@Nullable CBORObject header) {
        return header != null
                && header.getType() == CBORType.Map
                && header.get(COSE_LABEL_X5CHAIN) != null;
    }

    @Nullable
    private static Object toJava(@Nullable CBORObject value, int depth) {
        if (value == null) {
            return null;
        }
        if (depth > MAX_ELEMENT_VALUE_DEPTH) {
            throw new ProximityException("elementValue nesting exceeds " + MAX_ELEMENT_VALUE_DEPTH + " levels");
        }
        switch (value.getType()) {
            case TextString:
                return value.AsString();
            case Boolean:
                return value.AsBoolean();
            case ByteString:
                return value.GetByteString().clone();
            case Integer:
                return value.CanValueFitInInt64() ? (Object) value.AsInt64Value()
                        : value.ToObject(java.math.BigInteger.class);
            case FloatingPoint:
                return value.AsDoubleValue();
            case Array:
                return toJavaList(value, depth + 1);
            case Map:
                return toJavaMap(value, depth + 1);
            default:
                return value.ToJSONString();
        }
    }

    @NonNull
    private static List<Object> toJavaList(@NonNull CBORObject array, int depth) {
        List<Object> list = new ArrayList<>();
        for (int i = 0; i < array.size(); i++) {
            list.add(toJava(array.get(i), depth));
        }
        return Collections.unmodifiableList(list);
    }

    @NonNull
    private static Map<String, Object> toJavaMap(@NonNull CBORObject map, int depth) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (CBORObject key : map.getKeys()) {
            result.put(text(key, "elementValue map key"), toJava(map.get(key), depth));
        }
        return Collections.unmodifiableMap(result);
    }

    @NonNull
    private static String text(@Nullable CBORObject value, @NonNull String what) {
        if (value == null || value.getType() != CBORType.TextString) {
            throw new ProximityException(what + " is missing or is not a text string");
        }
        return value.AsString();
    }

    @NonNull
    private static CBORObject decode(@NonNull byte[] encoded) {
        try {
            return CBORObject.DecodeFromBytes(encoded);
        } catch (RuntimeException e) {
            throw new ProximityException("DeviceResponse is not valid CBOR", e);
        }
    }
}
