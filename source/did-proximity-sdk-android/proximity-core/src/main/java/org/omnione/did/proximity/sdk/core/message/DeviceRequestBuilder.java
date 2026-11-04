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

import com.upokecenter.cbor.CBORObject;

import org.omnione.did.proximity.sdk.core.cbor.CborTags;

import java.util.Map;

public final class DeviceRequestBuilder {

    private static final String VERSION_1_0 = "1.0";
    private static final String KEY_VERSION = "version";
    private static final String KEY_DOC_REQUESTS = "docRequests";
    private static final String KEY_ITEMS_REQUEST = "itemsRequest";
    private static final String KEY_DOC_TYPE = "docType";
    private static final String KEY_NAME_SPACES = "nameSpaces";

    private DeviceRequestBuilder() {}

    @NonNull
    public static byte[] build(@NonNull ItemsRequest request) {
        CBORObject nameSpaces = CBORObject.NewMap();
        for (Map.Entry<String, Map<String, Boolean>> namespace
                : request.requestedElements().entrySet()) {
            CBORObject elements = CBORObject.NewMap();
            for (Map.Entry<String, Boolean> element : namespace.getValue().entrySet()) {
                elements.Add(element.getKey(), element.getValue().booleanValue());
            }
            nameSpaces.Add(namespace.getKey(), elements);
        }

        CBORObject itemsRequest = CBORObject.NewMap();
        itemsRequest.Add(KEY_DOC_TYPE, request.docType());
        itemsRequest.Add(KEY_NAME_SPACES, nameSpaces);

        CBORObject docRequest = CBORObject.NewMap();
        docRequest.Add(KEY_ITEMS_REQUEST, CborTags.tagged(itemsRequest.EncodeToBytes()));

        CBORObject docRequests = CBORObject.NewArray();
        docRequests.Add(docRequest);

        CBORObject deviceRequest = CBORObject.NewMap();
        deviceRequest.Add(KEY_VERSION, VERSION_1_0);
        deviceRequest.Add(KEY_DOC_REQUESTS, docRequests);
        return deviceRequest.EncodeToBytes();
    }
}
