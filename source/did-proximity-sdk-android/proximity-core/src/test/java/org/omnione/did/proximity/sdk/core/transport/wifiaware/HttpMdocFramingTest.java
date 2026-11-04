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

package org.omnione.did.proximity.sdk.core.transport.wifiaware;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.omnione.did.proximity.sdk.core.ProximityException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public class HttpMdocFramingTest {

    private static String ascii(byte[] bytes) {
        return new String(bytes, StandardCharsets.ISO_8859_1);
    }

    private static byte[] ascii(String text) {
        return text.getBytes(StandardCharsets.ISO_8859_1);
    }

    private static InputStream in(byte[] bytes) {
        return new ByteArrayInputStream(bytes);
    }

    private static byte[] concat(byte[]... parts) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.write(part);
        }
        return out.toByteArray();
    }

    @Test
    public void requestIsAPostToMdocWithCborBodyAndCrlfHeaders() {
        byte[] frame = HttpMdocFraming.request("[fe80::1]", ascii("abc"));

        assertEquals("POST /mdoc HTTP/1.1\r\n"
                + "Host: [fe80::1]\r\n"
                + "Content-Length: 3\r\n"
                + "Content-Type: application/cbor\r\n"
                + "\r\n"
                + "abc", ascii(frame));
    }

    @Test
    public void responseIsA200WithCborBody() {
        byte[] frame = HttpMdocFraming.response(ascii("z"));

        assertEquals("HTTP/1.1 200 OK\r\n"
                + "Content-Length: 1\r\n"
                + "Content-Type: application/cbor\r\n"
                + "\r\n"
                + "z", ascii(frame));
    }

    @Test
    public void emptyResponseHasNoContentType() {
        assertEquals("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n",
                ascii(HttpMdocFraming.emptyResponse()));
    }

    @Test
    public void errorResponseCarriesTheStatusAndAnEmptyBody() {
        assertEquals("HTTP/1.1 411 Length Required\r\nContent-Length: 0\r\n\r\n",
                ascii(HttpMdocFraming.errorResponse(411)));
        assertTrue(ascii(HttpMdocFraming.errorResponse(413)).startsWith("HTTP/1.1 413 "));
        assertTrue(ascii(HttpMdocFraming.errorResponse(501)).startsWith("HTTP/1.1 501 "));
        assertTrue(ascii(HttpMdocFraming.errorResponse(400)).startsWith("HTTP/1.1 400 "));
    }

    @Test
    public void requestRoundTrips() throws IOException {
        byte[] body = new byte[]{0x10, 0x20, 0x30, 0x40};

        byte[] read = HttpMdocFraming.readRequestBody(
                in(HttpMdocFraming.request("[::1]", body)));

        assertArrayEquals(body, read);
    }

    @Test
    public void responseRoundTrips() throws IOException {
        byte[] body = new byte[]{0x7f};

        assertArrayEquals(body, HttpMdocFraming.readResponseBody(
                in(HttpMdocFraming.response(body))));
    }

    @Test
    public void headerNamesAreCaseInsensitive() throws IOException {
        byte[] frame = ascii("POST /mdoc HTTP/1.1\r\ncontent-length: 2\r\n"
                + "CONTENT-TYPE: application/CBOR\r\n\r\nab");

        assertArrayEquals(ascii("ab"), HttpMdocFraming.readRequestBody(in(frame)));
    }

    @Test
    public void bareLfLineEndingsAreAccepted() throws IOException {
        byte[] request = ascii("POST /mdoc HTTP/1.1\nHost: [::1]\nContent-Length: 2\n"
                + "Content-Type: application/CBOR\n\nxy");
        byte[] response = ascii("HTTP/1.1 200 OK\nContent-Length: 1\n"
                + "Content-Type: application/CBOR\n\nz");

        assertArrayEquals(ascii("xy"), HttpMdocFraming.readRequestBody(in(request)));
        assertArrayEquals(ascii("z"), HttpMdocFraming.readResponseBody(in(response)));
    }

    @Test
    public void eofBeforeTheFirstByteReadsAsNull() throws IOException {
        assertNull(HttpMdocFraming.readRequestBody(in(new byte[0])));
        assertNull(HttpMdocFraming.readResponseBody(in(new byte[0])));
    }

    @Test
    public void aTruncatedBodyIsAnError() {
        byte[] frame = ascii("POST /mdoc HTTP/1.1\r\nContent-Length: 5\r\n\r\nab");

        assertThrows(ProximityException.class, () -> HttpMdocFraming.readRequestBody(in(frame)));
    }

    @Test
    public void aTruncatedHeaderBlockIsAnError() {
        byte[] frame = ascii("POST /mdoc HTTP/1.1\r\nContent-Length: 5\r\n");

        assertThrows(ProximityException.class, () -> HttpMdocFraming.readRequestBody(in(frame)));
    }

    @Test
    public void aMissingContentLengthIsRejectedWith411() {
        byte[] frame = ascii("POST /mdoc HTTP/1.1\r\nContent-Type: application/cbor\r\n\r\n");

        HttpMdocFraming.RequestRejected e = assertThrows(HttpMdocFraming.RequestRejected.class,
                () -> HttpMdocFraming.readRequestBody(in(frame)));
        assertEquals(411, e.status());
    }

    @Test
    public void anOversizedContentLengthIsRejectedWith413() {
        byte[] frame = ascii("POST /mdoc HTTP/1.1\r\nContent-Length: " + ((1 << 20) + 1)
                + "\r\n\r\n");

        HttpMdocFraming.RequestRejected e = assertThrows(HttpMdocFraming.RequestRejected.class,
                () -> HttpMdocFraming.readRequestBody(in(frame)));
        assertEquals(413, e.status());
    }

    @Test
    public void chunkedTransferEncodingIsRejectedWith501() {
        byte[] frame = ascii("POST /mdoc HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n");

        HttpMdocFraming.RequestRejected e = assertThrows(HttpMdocFraming.RequestRejected.class,
                () -> HttpMdocFraming.readRequestBody(in(frame)));
        assertEquals(501, e.status());
    }

    @Test
    public void transferEncodingTogetherWithContentLengthIsRejectedWith400() {
        byte[] frame = ascii("POST /mdoc HTTP/1.1\r\nTransfer-Encoding: chunked\r\n"
                + "Content-Length: 1\r\n\r\nx");

        HttpMdocFraming.RequestRejected e = assertThrows(HttpMdocFraming.RequestRejected.class,
                () -> HttpMdocFraming.readRequestBody(in(frame)));
        assertEquals(400, e.status());
    }

    @Test
    public void duplicateOrNegativeContentLengthIsRejectedWith400() {
        byte[] duplicate = ascii("POST /mdoc HTTP/1.1\r\nContent-Length: 1\r\n"
                + "Content-Length: 1\r\n\r\nx");
        byte[] negative = ascii("POST /mdoc HTTP/1.1\r\nContent-Length: -1\r\n\r\n");
        byte[] garbage = ascii("POST /mdoc HTTP/1.1\r\nContent-Length: abc\r\n\r\n");

        for (byte[] frame : new byte[][]{duplicate, negative, garbage}) {
            HttpMdocFraming.RequestRejected e = assertThrows(
                    HttpMdocFraming.RequestRejected.class,
                    () -> HttpMdocFraming.readRequestBody(in(frame)));
            assertEquals(400, e.status());
        }
    }

    @Test
    public void aRequestThatIsNotAPostOfHttp11IsRejectedWith400() {
        byte[] get = ascii("GET /mdoc HTTP/1.1\r\nContent-Length: 0\r\n\r\n");
        byte[] http10 = ascii("POST /mdoc HTTP/1.0\r\nContent-Length: 0\r\n\r\n");

        for (byte[] frame : new byte[][]{get, http10}) {
            HttpMdocFraming.RequestRejected e = assertThrows(
                    HttpMdocFraming.RequestRejected.class,
                    () -> HttpMdocFraming.readRequestBody(in(frame)));
            assertEquals(400, e.status());
        }
    }

    @Test
    public void aNon200ResponseIsAnError() {
        byte[] frame = ascii("HTTP/1.1 500 Internal Server Error\r\nContent-Length: 0\r\n\r\n");

        assertThrows(ProximityException.class, () -> HttpMdocFraming.readResponseBody(in(frame)));
    }

    @Test
    public void aResponseWithoutContentLengthIsAnError() {
        byte[] frame = ascii("HTTP/1.1 200 OK\r\n\r\n");

        assertThrows(ProximityException.class, () -> HttpMdocFraming.readResponseBody(in(frame)));
    }

    @Test
    public void anEmptyResponseReadsAsAnEmptyBody() throws IOException {
        assertArrayEquals(new byte[0],
                HttpMdocFraming.readResponseBody(in(HttpMdocFraming.emptyResponse())));
    }

    @Test
    public void anOversizedHeaderBlockIsAnError() {
        StringBuilder header = new StringBuilder("POST /mdoc HTTP/1.1\r\n");
        while (header.length() < 9 * 1024) {
            header.append("X-Pad: ").append("p".repeat(1000)).append("\r\n");
        }
        header.append("Content-Length: 0\r\n\r\n");

        assertThrows(ProximityException.class,
                () -> HttpMdocFraming.readRequestBody(in(ascii(header.toString()))));
    }

    @Test
    public void twoRequestsOnOneStreamAreReadBackToBack() throws IOException {
        InputStream stream = in(concat(
                HttpMdocFraming.request("[::1]", ascii("first")),
                HttpMdocFraming.request("[::1]", ascii("second"))));

        assertArrayEquals(ascii("first"), HttpMdocFraming.readRequestBody(stream));
        assertArrayEquals(ascii("second"), HttpMdocFraming.readRequestBody(stream));
        assertNull(HttpMdocFraming.readRequestBody(stream));
    }
}
