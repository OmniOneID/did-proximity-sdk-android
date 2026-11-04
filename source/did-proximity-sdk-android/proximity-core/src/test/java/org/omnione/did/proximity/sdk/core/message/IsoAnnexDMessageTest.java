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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.upokecenter.cbor.CBORObject;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.cert.CertificateFactory;
import java.security.interfaces.ECPublicKey;
import java.util.Arrays;
import java.util.Map;

public class IsoAnnexDMessageTest {

    private static final String MDOC_REQUEST =
            "/iso18013-5-annex-d5-1-mdoc-request-plaintext.hex";
    private static final String MDOC_RESPONSE =
            "/iso18013-5-annex-d5-1-mdoc-response-plaintext.hex";

    private static final String DOC_TYPE = "org.iso.18013.5.1.mDL";
    private static final String NAME_SPACE = "org.iso.18013.5.1";

    @Test
    public void parsesTheIsoDeviceRequest() {
        ItemsRequest request = DeviceRequestParser.parse(resource(MDOC_REQUEST));

        assertEquals(DOC_TYPE, request.docType());
        assertEquals(1, request.requestedElements().size());

        Map<String, Boolean> elements = request.requestedElements().get(NAME_SPACE);
        assertNotNull(NAME_SPACE, elements);
        assertEquals(Arrays.asList(
                        "document_number", "driving_privileges", "expiry_date",
                        "family_name", "issue_date", "portrait"),
                sorted(elements));
    }

    @Test
    public void keepsTheReaderAuthOfTheIsoDeviceRequest() {
        ItemsRequest request = DeviceRequestParser.parse(resource(MDOC_REQUEST));

        assertNotNull("readerAuth", request.readerAuth());
        assertTrue(request.readerAuth().length > 0);
    }

    @Test
    public void parsesTheIsoDeviceResponse() {
        DeviceResponse response = DeviceResponseParser.parse(resource(MDOC_RESPONSE));

        assertEquals("1.0", response.version);
        assertEquals(0, response.status);
        assertEquals(1, response.documents.size());
        assertEquals(DOC_TYPE, response.documents.get(0).docType);
    }

    @Test
    public void parsesTheIsoIssuerSignedItems() {
        Document document = DeviceResponseParser.parse(resource(MDOC_RESPONSE)).documents.get(0);

        Map<String, Object> claims = document.flatClaims();

        assertEquals(Arrays.asList(
                        "document_number", "driving_privileges", "expiry_date",
                        "family_name", "issue_date", "portrait"),
                sorted(claims));
        assertEquals("Doe", claims.get("family_name"));
        assertEquals("123456789", claims.get("document_number"));
    }

    @Test
    public void parsesTheIsoPortraitAsBytes() {
        Document document = DeviceResponseParser.parse(resource(MDOC_RESPONSE)).documents.get(0);

        Object portrait = document.flatClaims().get("portrait");

        assertTrue(String.valueOf(portrait), portrait instanceof byte[]);
        assertEquals(1042, ((byte[]) portrait).length);
    }

    @Test
    public void detectsTheX5ChainOfTheIsoIssuerAuth() {
        Document document = DeviceResponseParser.parse(resource(MDOC_RESPONSE)).documents.get(0);

        assertTrue("hasX5Chain", document.hasX5Chain);
        assertNotNull("deviceSigned", document.deviceSigned);
    }

    @Test
    public void readsTheValidityDatesOfTheIsoMso() {
        Document document = DeviceResponseParser.parse(resource(MDOC_RESPONSE)).documents.get(0);

        assertEquals("2020-10-01T13:30:02Z", document.validFrom);
        assertEquals("2021-10-01T13:30:02Z", document.validUntil);
    }

    @Test
    public void readsTheDocTypeOfTheIsoMso() {
        Document document = DeviceResponseParser.parse(resource(MDOC_RESPONSE)).documents.get(0);

        assertEquals(DOC_TYPE, IssuerAuthVerifier.msoDocType(document.issuerAuth));
    }

    @Test
    public void theIsoIssuerAuthCarriesNoKid() {
        Document document = DeviceResponseParser.parse(resource(MDOC_RESPONSE)).documents.get(0);

        assertNull(IssuerAuthVerifier.kid(document.issuerAuth));
    }

    @Test
    public void theIsoIssuerAuthVerifiesAgainstItsDocumentSignerKey() throws Exception {
        Document document = DeviceResponseParser.parse(resource(MDOC_RESPONSE)).documents.get(0);

        assertTrue(IssuerAuthVerifier.verify(document.issuerAuth, documentSignerKey(document)));
    }

    @Test
    public void aFlippedIsoIssuerAuthSignatureByteDoesNotVerify() throws Exception {
        Document document = DeviceResponseParser.parse(resource(MDOC_RESPONSE)).documents.get(0);
        CBORObject issuerAuth = CBORObject.DecodeFromBytes(document.issuerAuth);
        byte[] signature = issuerAuth.get(3).GetByteString();
        signature[signature.length - 1] ^= 0x01;
        issuerAuth.set(3, CBORObject.FromObject(signature));

        assertFalse(IssuerAuthVerifier.verify(
                issuerAuth.EncodeToBytes(), documentSignerKey(document)));
    }

    private static ECPublicKey documentSignerKey(Document document) throws Exception {
        byte[] certificate = CBORObject.DecodeFromBytes(document.issuerAuth)
                .get(1).get(33).GetByteString();
        return (ECPublicKey) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(certificate)).getPublicKey();
    }

    private static java.util.List<String> sorted(Map<String, ?> map) {
        java.util.List<String> keys = new java.util.ArrayList<>(map.keySet());
        java.util.Collections.sort(keys);
        return keys;
    }

    private static byte[] resource(String name) {
        try (InputStream stream = IsoAnnexDMessageTest.class.getResourceAsStream(name)) {
            assertNotNull(name, stream);
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int read;
            while ((read = stream.read(chunk)) != -1) {
                buffer.write(chunk, 0, read);
            }
            String hex = new String(buffer.toByteArray(), "US-ASCII").replaceAll("\\s", "");
            byte[] bytes = new byte[hex.length() / 2];
            for (int i = 0; i < bytes.length; i++) {
                bytes[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
            }
            return bytes;
        } catch (IOException e) {
            throw new AssertionError(name, e);
        }
    }
}
