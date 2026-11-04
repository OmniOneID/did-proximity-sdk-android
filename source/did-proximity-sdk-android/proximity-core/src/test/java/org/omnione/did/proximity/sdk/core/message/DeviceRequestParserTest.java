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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.upokecenter.cbor.CBORObject;

import org.junit.Test;
import org.omnione.did.proximity.sdk.core.ProximityException;
import org.omnione.did.proximity.sdk.core.cbor.CborTags;

import java.util.Map;

public class DeviceRequestParserTest {

    private static final String MDL = "org.iso.18013.5.1.mDL";
    private static final String NS = "org.iso.18013.5.1";

    private static byte[] deviceRequest(boolean withReaderAuth) {
        CBORObject elements = CBORObject.NewMap();
        elements.Add("family_name", false);
        elements.Add("portrait", true);

        CBORObject nameSpaces = CBORObject.NewMap();
        nameSpaces.Add(NS, elements);

        CBORObject itemsRequest = CBORObject.NewMap();
        itemsRequest.Add("docType", MDL);
        itemsRequest.Add("nameSpaces", nameSpaces);

        CBORObject docRequest = CBORObject.NewMap();
        docRequest.Add("itemsRequest", CborTags.tagged(itemsRequest.EncodeToBytes()));
        if (withReaderAuth) {
            docRequest.Add("readerAuth", CBORObject.NewArray());
        }

        CBORObject docRequests = CBORObject.NewArray();
        docRequests.Add(docRequest);

        CBORObject request = CBORObject.NewMap();
        request.Add("version", "1.0");
        request.Add("docRequests", docRequests);
        return request.EncodeToBytes();
    }

    private static byte[] deviceRequestWithDocRequest(CBORObject docRequest) {
        CBORObject docRequests = CBORObject.NewArray();
        docRequests.Add(docRequest);

        CBORObject request = CBORObject.NewMap();
        request.Add("version", "1.0");
        request.Add("docRequests", docRequests);
        return request.EncodeToBytes();
    }

    private static byte[] deviceRequestWithItemsRequest(CBORObject itemsRequest) {
        CBORObject docRequest = CBORObject.NewMap();
        docRequest.Add("itemsRequest", CborTags.tagged(itemsRequest.EncodeToBytes()));
        return deviceRequestWithDocRequest(docRequest);
    }

    @Test
    public void parsesDocTypeAndElements() {
        ItemsRequest request = DeviceRequestParser.parse(deviceRequest(false));

        assertNotNull(request);
        assertEquals(MDL, request.docType());
        Map<String, Boolean> elements = request.requestedElements().get(NS);
        assertEquals(2, elements.size());
        assertEquals(Boolean.FALSE, elements.get("family_name"));
        assertEquals(Boolean.TRUE, elements.get("portrait"));
    }

    @Test
    public void readerAuthIsNullWhenAbsent() {
        assertNull(DeviceRequestParser.parse(deviceRequest(false)).readerAuth());
    }

    @Test
    public void readerAuthIsPassedThroughWhenPresent() {
        assertTrue(DeviceRequestParser.parse(deviceRequest(true)).readerAuth().length > 0);
    }

    @Test
    public void rejectsRequestWithoutDocRequests() {
        CBORObject request = CBORObject.NewMap();
        request.Add("version", "1.0");

        assertThrows(ProximityException.class,
                () -> DeviceRequestParser.parse(request.EncodeToBytes()));
    }

    @Test
    public void rejectsNonMapTopLevel() {
        byte[] bytes = CBORObject.FromObject(42).EncodeToBytes();

        assertThrows(ProximityException.class,
                () -> DeviceRequestParser.parse(bytes));
    }

    @Test
    public void rejectsDocRequestThatIsNotAMap() {
        CBORObject docRequests = CBORObject.NewArray();
        docRequests.Add(CBORObject.FromObject("not a map"));

        CBORObject request = CBORObject.NewMap();
        request.Add("version", "1.0");
        request.Add("docRequests", docRequests);

        assertThrows(ProximityException.class,
                () -> DeviceRequestParser.parse(request.EncodeToBytes()));
    }

    @Test
    public void rejectsItemsRequestWithInvalidCborInsideTag() {
        byte[] garbage = new byte[]{(byte) 0xff, (byte) 0xff};

        CBORObject docRequest = CBORObject.NewMap();
        docRequest.Add("itemsRequest", CborTags.tagged(garbage));

        byte[] bytes = deviceRequestWithDocRequest(docRequest);

        assertThrows(ProximityException.class,
                () -> DeviceRequestParser.parse(bytes));
    }

    @Test
    public void rejectsItemsRequestThatIsNotAMap() {
        CBORObject notAMap = CBORObject.NewArray();
        notAMap.Add("something");

        byte[] bytes = deviceRequestWithItemsRequest(notAMap);

        assertThrows(ProximityException.class,
                () -> DeviceRequestParser.parse(bytes));
    }

    @Test
    public void rejectsNameSpaceValueThatIsNotAMap() {
        CBORObject nameSpaces = CBORObject.NewMap();
        nameSpaces.Add(NS, "not a map");

        CBORObject itemsRequest = CBORObject.NewMap();
        itemsRequest.Add("docType", MDL);
        itemsRequest.Add("nameSpaces", nameSpaces);

        byte[] bytes = deviceRequestWithItemsRequest(itemsRequest);

        assertThrows(ProximityException.class,
                () -> DeviceRequestParser.parse(bytes));
    }

    @Test
    public void rejectsElementIdentifierThatIsNotATextString() {
        CBORObject elements = CBORObject.NewMap();
        elements.Add(42, true);

        CBORObject nameSpaces = CBORObject.NewMap();
        nameSpaces.Add(NS, elements);

        CBORObject itemsRequest = CBORObject.NewMap();
        itemsRequest.Add("docType", MDL);
        itemsRequest.Add("nameSpaces", nameSpaces);

        byte[] bytes = deviceRequestWithItemsRequest(itemsRequest);

        assertThrows(ProximityException.class,
                () -> DeviceRequestParser.parse(bytes));
    }

    @Test
    public void rejectsIntentToRetainThatIsNotABoolean() {
        CBORObject elements = CBORObject.NewMap();
        elements.Add("family_name", "yes");

        CBORObject nameSpaces = CBORObject.NewMap();
        nameSpaces.Add(NS, elements);

        CBORObject itemsRequest = CBORObject.NewMap();
        itemsRequest.Add("docType", MDL);
        itemsRequest.Add("nameSpaces", nameSpaces);

        byte[] bytes = deviceRequestWithItemsRequest(itemsRequest);

        assertThrows(ProximityException.class,
                () -> DeviceRequestParser.parse(bytes));
    }

    @Test
    public void rejectsPutOnInnerRequestedElementsMap() {
        Map<String, Boolean> inner = DeviceRequestParser.parse(deviceRequest(false))
                .requestedElements().get(NS);

        assertThrows(UnsupportedOperationException.class,
                () -> inner.put("family_name", true));
    }

    @Test
    public void rejectsPutOnOuterRequestedElementsMap() {
        Map<String, Map<String, Boolean>> outer = DeviceRequestParser.parse(deviceRequest(false))
                .requestedElements();

        assertThrows(UnsupportedOperationException.class,
                () -> outer.put(NS, null));
    }

    @Test
    public void rejectsItemsRequestWithoutNameSpaces() {
        CBORObject itemsRequest = CBORObject.NewMap();
        itemsRequest.Add("docType", MDL);

        byte[] bytes = deviceRequestWithItemsRequest(itemsRequest);

        assertThrows(ProximityException.class,
                () -> DeviceRequestParser.parse(bytes));
    }

    @Test
    public void rejectsNameSpacesThatIsNotAMap() {
        CBORObject itemsRequest = CBORObject.NewMap();
        itemsRequest.Add("docType", MDL);
        itemsRequest.Add("nameSpaces", CBORObject.NewArray());

        byte[] bytes = deviceRequestWithItemsRequest(itemsRequest);

        assertThrows(ProximityException.class,
                () -> DeviceRequestParser.parse(bytes));
    }

    @Test
    public void acceptsEmptyNameSpaces() {
        CBORObject itemsRequest = CBORObject.NewMap();
        itemsRequest.Add("docType", MDL);
        itemsRequest.Add("nameSpaces", CBORObject.NewMap());

        byte[] bytes = deviceRequestWithItemsRequest(itemsRequest);

        ItemsRequest parsed = DeviceRequestParser.parse(bytes);
        assertTrue(parsed.requestedElements().isEmpty());
    }

    @Test
    public void acceptsDeviceRequestCarryingMoreThanOneDocRequest() {
        CBORObject docRequests = CBORObject.NewArray();
        docRequests.Add(docRequestOf("org.iso.18013.5.1.mDL", "org.iso.18013.5.1", "family_name"));
        docRequests.Add(docRequestOf("org.omnione.did.mvc", "org.omnione.did.mvc.1", "plate"));

        CBORObject request = CBORObject.NewMap();
        request.Add("version", "1.0");
        request.Add("docRequests", docRequests);

        ItemsRequest parsed = DeviceRequestParser.parse(request.EncodeToBytes());

        assertEquals("org.iso.18013.5.1.mDL", parsed.docType());
        assertEquals(1, parsed.requestedElements().size());
        assertEquals(Boolean.FALSE,
                parsed.requestedElements().get("org.iso.18013.5.1").get("family_name"));
    }

    private static CBORObject docRequestOf(String docType, String nameSpace, String element) {
        CBORObject elements = CBORObject.NewMap();
        elements.Add(element, false);

        CBORObject nameSpaces = CBORObject.NewMap();
        nameSpaces.Add(nameSpace, elements);

        CBORObject itemsRequest = CBORObject.NewMap();
        itemsRequest.Add("docType", docType);
        itemsRequest.Add("nameSpaces", nameSpaces);

        CBORObject docRequest = CBORObject.NewMap();
        docRequest.Add("itemsRequest", CborTags.tagged(itemsRequest.EncodeToBytes()));
        return docRequest;
    }
}
