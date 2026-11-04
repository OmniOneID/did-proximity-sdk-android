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

package org.omnione.did.proximity.sdk.reader;

import com.upokecenter.cbor.CBORObject;

import org.omnione.did.proximity.sdk.core.cbor.CborTags;

final class DeviceResponseFixture {

    private DeviceResponseFixture() {}

    static byte[] of(String docType, String elementIdentifier, String elementValue) {
        CBORObject item = CBORObject.NewMap();
        item.Add("digestID", 1);
        item.Add("random", new byte[]{0x01, 0x02});
        item.Add("elementIdentifier", elementIdentifier);
        item.Add("elementValue", elementValue);

        CBORObject items = CBORObject.NewArray();
        items.Add(CborTags.tagged(item.EncodeToBytes()));
        CBORObject nameSpaces = CBORObject.NewMap();
        nameSpaces.Add(docType, items);

        CBORObject issuerAuth = CBORObject.NewArray();
        issuerAuth.Add(CBORObject.NewMap().EncodeToBytes());
        issuerAuth.Add(CBORObject.NewMap());
        issuerAuth.Add(new byte[]{0x00});
        issuerAuth.Add(new byte[]{0x00});

        CBORObject issuerSigned = CBORObject.NewMap();
        issuerSigned.Add("issuerAuth", issuerAuth);
        issuerSigned.Add("nameSpaces", nameSpaces);

        CBORObject document = CBORObject.NewMap();
        document.Add("docType", docType);
        document.Add("issuerSigned", issuerSigned);

        CBORObject documents = CBORObject.NewArray();
        documents.Add(document);

        CBORObject response = CBORObject.NewMap();
        response.Add("version", "1.0");
        response.Add("documents", documents);
        response.Add("status", 0);
        return response.EncodeToBytes();
    }
}
