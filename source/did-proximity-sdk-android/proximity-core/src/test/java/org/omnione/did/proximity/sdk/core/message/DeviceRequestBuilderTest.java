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

import com.upokecenter.cbor.CBORObject;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

public class DeviceRequestBuilderTest {

    private static final String DOC_TYPE = "eu.europa.ec.eudi.pid.1";
    private static final String NAMESPACE = "eu.europa.ec.eudi.pid.1";

    private static ItemsRequest sample() {
        Map<String, Boolean> elements = new LinkedHashMap<>();
        elements.put("family_name", false);
        elements.put("birth_date", true);
        Map<String, Map<String, Boolean>> nameSpaces = new LinkedHashMap<>();
        nameSpaces.put(NAMESPACE, elements);
        return ItemsRequest.of(DOC_TYPE, nameSpaces);
    }

    @Test
    public void buildRoundTripsThroughOurOwnParser() {
        byte[] encoded = DeviceRequestBuilder.build(sample());

        ItemsRequest parsed = DeviceRequestParser.parse(encoded);

        assertEquals(DOC_TYPE, parsed.docType());
        assertEquals(Boolean.FALSE, parsed.requestedElements().get(NAMESPACE).get("family_name"));
        assertEquals(Boolean.TRUE, parsed.requestedElements().get(NAMESPACE).get("birth_date"));
    }

    @Test
    public void buildOmitsReaderAuth() {
        ItemsRequest parsed = DeviceRequestParser.parse(DeviceRequestBuilder.build(sample()));

        assertEquals(null, parsed.readerAuth());
    }

    @Test
    public void builtRequestDeclaresVersionOneZero() {
        CBORObject decoded = CBORObject.DecodeFromBytes(DeviceRequestBuilder.build(sample()));

        assertEquals("1.0", decoded.get("version").AsString());
        assertEquals(1, decoded.get("docRequests").size());
    }
}
