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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.upokecenter.cbor.CBORObject;

import org.junit.Test;
import org.omnione.did.proximity.sdk.core.ProximityException;
import org.omnione.did.proximity.sdk.core.cbor.CborTags;

import java.util.Collections;
import java.util.List;
import java.util.Map;

public class DeviceResponseParserTest {

    private static final int COSE_LABEL_X5CHAIN = 33;
    private static final CBORObject DEVICE_SIGNED = CBORObject.FromObject(new byte[]{0x0D, 0x0E, 0x0F});

    private static CBORObject issuerSignedItemRaw(String identifier, CBORObject value) {
        CBORObject item = CBORObject.NewMap();
        item.Add("digestID", 1);
        item.Add("random", new byte[]{0x01, 0x02});
        item.Add("elementIdentifier", identifier);
        item.Add("elementValue", value);
        return CborTags.tagged(item.EncodeToBytes());
    }

    private static CBORObject issuerSignedItem(String identifier, Object value) {
        return issuerSignedItemRaw(identifier, CBORObject.FromObject(value));
    }

    private static CBORObject nestedArrayValue(int depth) {
        CBORObject value = CBORObject.FromObject("leaf");
        for (int i = 0; i < depth; i++) {
            CBORObject wrapper = CBORObject.NewArray();
            wrapper.Add(value);
            value = wrapper;
        }
        return value;
    }

    private static CBORObject issuerAuth(boolean withX5Chain, boolean inProtected) {
        return issuerAuth(withX5Chain, inProtected, new byte[]{0x00});
    }

    private static CBORObject issuerAuth(boolean withX5Chain, boolean inProtected, byte[] payload) {
        CBORObject protectedHeader = CBORObject.NewMap();
        protectedHeader.Add(1, -7);
        CBORObject unprotectedHeader = CBORObject.NewMap();
        if (withX5Chain) {
            CBORObject chain = CBORObject.FromObject(new byte[]{0x0A});
            if (inProtected) {
                protectedHeader.Add(COSE_LABEL_X5CHAIN, chain);
            } else {
                unprotectedHeader.Add(COSE_LABEL_X5CHAIN, chain);
            }
        }
        CBORObject sign1 = CBORObject.NewArray();
        sign1.Add(protectedHeader.EncodeToBytes());
        sign1.Add(unprotectedHeader);
        sign1.Add(payload);
        sign1.Add(new byte[]{0x00});
        return sign1;
    }

    private static byte[] msoPayload(CBORObject validFrom, CBORObject validUntil) {
        CBORObject validityInfo = CBORObject.NewMap();
        validityInfo.Add("signed", validFrom);
        validityInfo.Add("validFrom", validFrom);
        validityInfo.Add("validUntil", validUntil);
        CBORObject mso = CBORObject.NewMap();
        mso.Add("version", "1.0");
        mso.Add("docType", "eu.europa.ec.eudi.pid.1");
        mso.Add("validityInfo", validityInfo);
        return CborTags.tagged(mso.EncodeToBytes()).EncodeToBytes();
    }

    private static CBORObject tdate(String value) {
        return CBORObject.FromObject(value).WithTag(0);
    }

    private static byte[] responseWithNameSpaces(CBORObject auth, CBORObject nameSpaces) {
        CBORObject issuerSigned = CBORObject.NewMap();
        issuerSigned.Add("issuerAuth", auth);
        issuerSigned.Add("nameSpaces", nameSpaces);

        CBORObject document = CBORObject.NewMap();
        document.Add("docType", "eu.europa.ec.eudi.pid.1");
        document.Add("issuerSigned", issuerSigned);
        document.Add("deviceSigned", DEVICE_SIGNED);

        CBORObject documents = CBORObject.NewArray();
        documents.Add(document);

        CBORObject deviceResponse = CBORObject.NewMap();
        deviceResponse.Add("version", "1.0");
        deviceResponse.Add("documents", documents);
        deviceResponse.Add("status", 0);
        return deviceResponse.EncodeToBytes();
    }

    private static byte[] response(CBORObject auth, boolean withDocuments) {
        if (!withDocuments) {
            CBORObject deviceResponse = CBORObject.NewMap();
            deviceResponse.Add("version", "1.0");
            deviceResponse.Add("status", 0);
            return deviceResponse.EncodeToBytes();
        }
        CBORObject items = CBORObject.NewArray();
        items.Add(issuerSignedItem("family_name", "Kim"));
        items.Add(issuerSignedItem("age_over_18", true));
        CBORObject nameSpaces = CBORObject.NewMap();
        nameSpaces.Add("eu.europa.ec.eudi.pid.1", items);
        return responseWithNameSpaces(auth, nameSpaces);
    }

    @Test
    public void parseReadsDocTypeAndClaims() {
        DeviceResponse parsed = DeviceResponseParser.parse(response(issuerAuth(false, false), true));

        assertEquals(1, parsed.documents.size());
        Document document = parsed.documents.get(0);
        assertEquals("eu.europa.ec.eudi.pid.1", document.docType);
        assertEquals("Kim", document.flatClaims().get("family_name"));
        assertEquals(Boolean.TRUE, document.flatClaims().get("age_over_18"));
    }

    @Test
    public void parseKeepsTheIssuerAuthBytes() {
        CBORObject auth = issuerAuth(false, false);
        DeviceResponse parsed = DeviceResponseParser.parse(response(auth, true));

        assertArrayEquals(auth.EncodeToBytes(), parsed.documents.get(0).issuerAuth);
    }

    @Test
    public void parseKeepsTheDeviceSignedBytes() {
        DeviceResponse parsed = DeviceResponseParser.parse(response(issuerAuth(false, false), true));

        assertArrayEquals(DEVICE_SIGNED.EncodeToBytes(), parsed.documents.get(0).deviceSigned);
    }

    @Test
    public void parseReadsTheMsoValidityDates() {
        CBORObject auth = issuerAuth(false, false,
                msoPayload(tdate("2026-08-04T00:00:00Z"), tdate("2026-11-02T00:00:00Z")));
        DeviceResponse parsed = DeviceResponseParser.parse(response(auth, true));

        assertEquals("2026-08-04T00:00:00Z", parsed.documents.get(0).validFrom);
        assertEquals("2026-11-02T00:00:00Z", parsed.documents.get(0).validUntil);
    }

    @Test
    public void parseReadsEpochSecondValidityDates() {
        CBORObject auth = issuerAuth(false, false,
                msoPayload(CBORObject.FromObject(1785801600L), CBORObject.FromObject(1793577600L)));
        DeviceResponse parsed = DeviceResponseParser.parse(response(auth, true));

        assertEquals("2026-08-04T00:00:00Z", parsed.documents.get(0).validFrom);
        assertEquals("2026-11-02T00:00:00Z", parsed.documents.get(0).validUntil);
    }

    @Test
    public void anOutOfRangeEpochValidityDateIsLeftNull() {
        CBORObject auth = issuerAuth(false, false,
                msoPayload(CBORObject.FromObject(100000000000000000L), CBORObject.FromObject(1793577600L)));
        DeviceResponse parsed = DeviceResponseParser.parse(response(auth, true));

        assertNull(parsed.documents.get(0).validFrom);
        assertEquals("2026-11-02T00:00:00Z", parsed.documents.get(0).validUntil);
    }

    @Test
    public void aPayloadWithoutAnMsoLeavesTheValidityDatesNull() {
        DeviceResponse parsed = DeviceResponseParser.parse(response(issuerAuth(false, false), true));

        assertNull(parsed.documents.get(0).validFrom);
        assertNull(parsed.documents.get(0).validUntil);
    }

    @Test
    public void aResponseWithoutAnX5ChainIsFlaggedFalse() {
        DeviceResponse parsed = DeviceResponseParser.parse(response(issuerAuth(false, false), true));

        assertFalse(parsed.documents.get(0).hasX5Chain);
    }

    @Test
    public void anX5ChainInTheUnprotectedHeaderIsFlagged() {
        DeviceResponse parsed = DeviceResponseParser.parse(response(issuerAuth(true, false), true));

        assertTrue(parsed.documents.get(0).hasX5Chain);
    }

    @Test
    public void anX5ChainInTheProtectedHeaderIsFlagged() {
        DeviceResponse parsed = DeviceResponseParser.parse(response(issuerAuth(true, true), true));

        assertTrue(parsed.documents.get(0).hasX5Chain);
    }

    @Test
    public void aResponseWithoutDocumentsIsStillParsed() {
        DeviceResponse parsed = DeviceResponseParser.parse(response(issuerAuth(false, false), false));

        assertTrue(parsed.documents.isEmpty());
        assertEquals(0, parsed.status);
    }

    @Test
    public void parseRejectsGarbage() {
        assertThrows(ProximityException.class,
                () -> DeviceResponseParser.parse(new byte[]{0x00, 0x01}));
    }

    @Test
    public void parseRejectsANonTextNamespaceKey() {
        CBORObject items = CBORObject.NewArray();
        items.Add(issuerSignedItem("family_name", "Kim"));
        CBORObject nameSpaces = CBORObject.NewMap();
        nameSpaces.Add(1, items);

        byte[] encoded = responseWithNameSpaces(issuerAuth(false, false), nameSpaces);

        assertThrows(ProximityException.class, () -> DeviceResponseParser.parse(encoded));
    }

    @Test
    public void documentCopiesTheIssuerAuthArrayOnConstruction() {
        byte[] source = new byte[]{0x01, 0x02, 0x03};
        Document document = new Document("docType", Collections.emptyMap(), source, null, false, null, null);

        source[0] = (byte) 0xFF;

        assertArrayEquals(new byte[]{0x01, 0x02, 0x03}, document.issuerAuth);
    }

    @Test
    public void documentCopiesTheDeviceSignedArrayOnConstruction() {
        byte[] source = new byte[]{0x04, 0x05, 0x06};
        Document document = new Document("docType", Collections.emptyMap(), new byte[]{0x00}, source, false, null, null);

        source[0] = (byte) 0xFF;

        assertArrayEquals(new byte[]{0x04, 0x05, 0x06}, document.deviceSigned);
    }

    @Test
    public void parseMapsIntegerClaimValues() {
        CBORObject items = CBORObject.NewArray();
        items.Add(issuerSignedItem("age_in_years", 42));
        CBORObject nameSpaces = CBORObject.NewMap();
        nameSpaces.Add("org.iso.18013.5.1", items);

        DeviceResponse parsed =
                DeviceResponseParser.parse(responseWithNameSpaces(issuerAuth(false, false), nameSpaces));

        assertEquals(42L, parsed.documents.get(0).flatClaims().get("age_in_years"));
    }

    @Test
    public void parseMapsFloatingPointClaimValues() {
        CBORObject items = CBORObject.NewArray();
        items.Add(issuerSignedItem("score", 1.5d));
        CBORObject nameSpaces = CBORObject.NewMap();
        nameSpaces.Add("org.iso.18013.5.1", items);

        DeviceResponse parsed =
                DeviceResponseParser.parse(responseWithNameSpaces(issuerAuth(false, false), nameSpaces));

        assertEquals(1.5d, (Double) parsed.documents.get(0).flatClaims().get("score"), 0.0001);
    }

    @Test
    public void parseMapsByteStringClaimValues() {
        CBORObject items = CBORObject.NewArray();
        items.Add(issuerSignedItem("portrait", new byte[]{0x0A, 0x0B}));
        CBORObject nameSpaces = CBORObject.NewMap();
        nameSpaces.Add("org.iso.18013.5.1", items);

        DeviceResponse parsed =
                DeviceResponseParser.parse(responseWithNameSpaces(issuerAuth(false, false), nameSpaces));

        assertArrayEquals(new byte[]{0x0A, 0x0B},
                (byte[]) parsed.documents.get(0).flatClaims().get("portrait"));
    }

    @Test
    public void parseMapsArrayClaimValuesToLists() {
        CBORObject entry = CBORObject.NewMap();
        entry.Add("vehicle_category_code", "B");
        CBORObject privileges = CBORObject.NewArray();
        privileges.Add(entry);

        CBORObject items = CBORObject.NewArray();
        items.Add(issuerSignedItemRaw("driving_privileges", privileges));
        CBORObject nameSpaces = CBORObject.NewMap();
        nameSpaces.Add("org.iso.18013.5.1", items);

        DeviceResponse parsed =
                DeviceResponseParser.parse(responseWithNameSpaces(issuerAuth(false, false), nameSpaces));

        Object claim = parsed.documents.get(0).flatClaims().get("driving_privileges");
        assertTrue(claim instanceof List);
        List<?> list = (List<?>) claim;
        assertEquals(1, list.size());
        assertTrue(list.get(0) instanceof Map);
        assertEquals("B", ((Map<?, ?>) list.get(0)).get("vehicle_category_code"));
    }

    @Test
    public void parseMapsMapClaimValuesToMaps() {
        CBORObject value = CBORObject.NewMap();
        value.Add("key", "value");

        CBORObject items = CBORObject.NewArray();
        items.Add(issuerSignedItemRaw("nested", value));
        CBORObject nameSpaces = CBORObject.NewMap();
        nameSpaces.Add("org.iso.18013.5.1", items);

        DeviceResponse parsed =
                DeviceResponseParser.parse(responseWithNameSpaces(issuerAuth(false, false), nameSpaces));

        Object claim = parsed.documents.get(0).flatClaims().get("nested");
        assertTrue(claim instanceof Map);
        assertEquals("value", ((Map<?, ?>) claim).get("key"));
    }

    @Test
    public void parseRejectsANonTextKeyInsideAMapClaimValue() {
        CBORObject value = CBORObject.NewMap();
        value.Add(1, "value");

        CBORObject items = CBORObject.NewArray();
        items.Add(issuerSignedItemRaw("nested", value));
        CBORObject nameSpaces = CBORObject.NewMap();
        nameSpaces.Add("org.iso.18013.5.1", items);

        byte[] encoded = responseWithNameSpaces(issuerAuth(false, false), nameSpaces);

        assertThrows(ProximityException.class, () -> DeviceResponseParser.parse(encoded));
    }

    @Test
    public void parseAllowsNestingUpToTheDepthLimit() {
        CBORObject items = CBORObject.NewArray();
        items.Add(issuerSignedItemRaw("nested", nestedArrayValue(32)));
        CBORObject nameSpaces = CBORObject.NewMap();
        nameSpaces.Add("org.iso.18013.5.1", items);

        DeviceResponse parsed =
                DeviceResponseParser.parse(responseWithNameSpaces(issuerAuth(false, false), nameSpaces));

        assertTrue(parsed.documents.get(0).flatClaims().get("nested") instanceof List);
    }

    @Test
    public void parseRejectsNestingBeyondTheDepthLimit() {
        CBORObject items = CBORObject.NewArray();
        items.Add(issuerSignedItemRaw("nested", nestedArrayValue(33)));
        CBORObject nameSpaces = CBORObject.NewMap();
        nameSpaces.Add("org.iso.18013.5.1", items);

        byte[] encoded = responseWithNameSpaces(issuerAuth(false, false), nameSpaces);

        assertThrows(ProximityException.class, () -> DeviceResponseParser.parse(encoded));
    }

    @Test
    public void nestedArrayAndMapClaimValuesAreUnmodifiable() {
        CBORObject entry = CBORObject.NewMap();
        entry.Add("vehicle_category_code", "B");
        CBORObject privileges = CBORObject.NewArray();
        privileges.Add(entry);

        CBORObject items = CBORObject.NewArray();
        items.Add(issuerSignedItemRaw("driving_privileges", privileges));
        CBORObject nameSpaces = CBORObject.NewMap();
        nameSpaces.Add("org.iso.18013.5.1", items);

        DeviceResponse parsed =
                DeviceResponseParser.parse(responseWithNameSpaces(issuerAuth(false, false), nameSpaces));
        Document document = parsed.documents.get(0);

        @SuppressWarnings("unchecked")
        List<Object> outer = (List<Object>) document.flatClaims().get("driving_privileges");
        assertThrows(UnsupportedOperationException.class, outer::clear);
        assertThrows(UnsupportedOperationException.class, () -> outer.add("INJECTED"));

        @SuppressWarnings("unchecked")
        Map<String, Object> nested = (Map<String, Object>) outer.get(0);
        assertThrows(UnsupportedOperationException.class, nested::clear);
        assertThrows(UnsupportedOperationException.class, () -> nested.put("INJECTED", "x"));

        List<?> stillOuter = (List<?>) document.flatClaims().get("driving_privileges");
        assertEquals(1, stillOuter.size());
        Map<?, ?> stillNested = (Map<?, ?>) stillOuter.get(0);
        assertEquals("B", stillNested.get("vehicle_category_code"));
    }
}
