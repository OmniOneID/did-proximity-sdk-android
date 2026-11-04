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

import com.upokecenter.cbor.CBORException;
import com.upokecenter.cbor.CBORObject;
import com.upokecenter.cbor.CBORType;

import org.omnione.did.proximity.sdk.core.ProximityException;
import org.omnione.did.proximity.sdk.core.cbor.CborTags;

import java.util.LinkedHashMap;
import java.util.Map;

public final class DeviceRequestParser {

    private static final String KEY_DOC_REQUESTS = "docRequests";
    private static final String KEY_ITEMS_REQUEST = "itemsRequest";
    private static final String KEY_READER_AUTH = "readerAuth";
    private static final String KEY_DOC_TYPE = "docType";
    private static final String KEY_NAME_SPACES = "nameSpaces";

    private DeviceRequestParser() {}

    @NonNull
    public static ItemsRequest parse(@NonNull byte[] deviceRequest) {
        CBORObject request;
        try {
            request = CBORObject.DecodeFromBytes(deviceRequest);
        } catch (CBORException e) {
            throw new ProximityException("malformed DeviceRequest", e);
        }
        requireMap(request, "DeviceRequest");

        CBORObject docRequests = request.get(KEY_DOC_REQUESTS);
        if (docRequests == null || docRequests.getType() != CBORType.Array
                || docRequests.size() == 0) {
            throw new ProximityException("DeviceRequest has no docRequests");
        }
        return parseDocRequest(docRequests.get(0));
    }

    @NonNull
    private static ItemsRequest parseDocRequest(@NonNull CBORObject docRequest) {
        requireMap(docRequest, "DocRequest");

        CBORObject wrapped = docRequest.get(KEY_ITEMS_REQUEST);
        if (wrapped == null) {
            throw new ProximityException("DocRequest has no itemsRequest");
        }
        CBORObject itemsRequest;
        try {
            itemsRequest = CBORObject.DecodeFromBytes(CborTags.untag(wrapped));
        } catch (ProximityException e) {
            throw e;
        } catch (Exception e) {
            throw new ProximityException("malformed ItemsRequest", e);
        }
        requireMap(itemsRequest, "ItemsRequest");

        CBORObject docType = itemsRequest.get(KEY_DOC_TYPE);
        if (docType == null || docType.getType() != CBORType.TextString) {
            throw new ProximityException("ItemsRequest has no docType");
        }

        CBORObject nameSpaces = itemsRequest.get(KEY_NAME_SPACES);
        if (nameSpaces == null) {
            throw new ProximityException("ItemsRequest has no nameSpaces");
        }
        requireMap(nameSpaces, "nameSpaces");

        Map<String, Map<String, Boolean>> elements = new LinkedHashMap<>();
        for (CBORObject nameSpace : nameSpaces.getKeys()) {
            CBORObject entries = nameSpaces.get(nameSpace);
            requireMap(entries, "nameSpace entries");

            Map<String, Boolean> perNameSpace = new LinkedHashMap<>();
            for (CBORObject element : entries.getKeys()) {
                perNameSpace.put(requireTextString(element, "elementIdentifier"),
                        requireBoolean(entries.get(element), "intentToRetain"));
            }
            elements.put(requireTextString(nameSpace, "nameSpace"), perNameSpace);
        }

        CBORObject readerAuth = docRequest.get(KEY_READER_AUTH);
        return new ItemsRequest(docType.AsString(), elements,
                readerAuth == null ? null : readerAuth.EncodeToBytes());
    }

    private static void requireMap(@NonNull CBORObject value, @NonNull String what) {
        if (value.getType() != CBORType.Map) {
            throw new ProximityException(what + " is not a map, got " + value.getType());
        }
    }

    @NonNull
    private static String requireTextString(@NonNull CBORObject value, @NonNull String what) {
        if (value.getType() != CBORType.TextString) {
            throw new ProximityException(what + " is not a text string, got " + value.getType());
        }
        return value.AsString();
    }

    private static boolean requireBoolean(@NonNull CBORObject value, @NonNull String what) {
        if (value.getType() != CBORType.Boolean) {
            throw new ProximityException(what + " is not a boolean, got " + value.getType());
        }
        return value.AsBoolean();
    }
}
