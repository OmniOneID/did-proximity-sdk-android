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

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.omnione.did.proximity.sdk.core.ProximityException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class HttpMdocFraming {

    public static final int MAX_BODY = 1 << 20;
    static final int MAX_HEADER = 8 << 10;

    private static final String CRLF = "\r\n";
    private static final String REQUEST_LINE = "POST /mdoc HTTP/1.1";
    private static final String STATUS_OK = "HTTP/1.1 200 OK";
    private static final String CONTENT_LENGTH = "content-length";
    private static final String TRANSFER_ENCODING = "transfer-encoding";

    public static final class RequestRejected extends ProximityException {
        private final int status;

        RequestRejected(int status, @NonNull String message) {
            super(message);
            this.status = status;
        }

        public int status() {
            return status;
        }
    }

    private HttpMdocFraming() {}

    @NonNull
    public static byte[] request(@NonNull String hostHeader, @NonNull byte[] body) {
        return frame(REQUEST_LINE + CRLF
                + "Host: " + hostHeader + CRLF
                + "Content-Length: " + body.length + CRLF
                + "Content-Type: application/cbor" + CRLF
                + CRLF, body);
    }

    @NonNull
    public static byte[] response(@NonNull byte[] body) {
        return frame(STATUS_OK + CRLF
                + "Content-Length: " + body.length + CRLF
                + "Content-Type: application/cbor" + CRLF
                + CRLF, body);
    }

    @NonNull
    public static byte[] emptyResponse() {
        return ascii(STATUS_OK + CRLF + "Content-Length: 0" + CRLF + CRLF);
    }

    @NonNull
    public static byte[] errorResponse(int status) {
        return ascii("HTTP/1.1 " + status + " " + reason(status) + CRLF
                + "Content-Length: 0" + CRLF + CRLF);
    }

    @NonNull
    private static String reason(int status) {
        switch (status) {
            case 400: return "Bad Request";
            case 411: return "Length Required";
            case 413: return "Content Too Large";
            case 501: return "Not Implemented";
            default: return "Error";
        }
    }

    @NonNull
    private static byte[] frame(@NonNull String head, @NonNull byte[] body) {
        byte[] header = ascii(head);
        byte[] out = new byte[header.length + body.length];
        System.arraycopy(header, 0, out, 0, header.length);
        System.arraycopy(body, 0, out, header.length, body.length);
        return out;
    }

    @NonNull
    private static byte[] ascii(@NonNull String text) {
        return text.getBytes(StandardCharsets.ISO_8859_1);
    }

    @Nullable
    public static byte[] readRequestBody(@NonNull InputStream in) throws IOException {
        List<String> lines = readHead(in);
        if (lines == null) {
            return null;
        }
        String[] start = lines.get(0).split(" ");
        if (start.length != 3 || !"POST".equals(start[0]) || !"HTTP/1.1".equals(start[2])) {
            throw new RequestRejected(400, "unsupported request line: " + lines.get(0));
        }
        int length = requestLength(lines);
        if (length > MAX_BODY) {
            throw new RequestRejected(413, "request body of " + length + " bytes exceeds "
                    + MAX_BODY);
        }
        return readFully(in, length);
    }

    @Nullable
    public static byte[] readResponseBody(@NonNull InputStream in) throws IOException {
        List<String> lines = readHead(in);
        if (lines == null) {
            return null;
        }
        String[] start = lines.get(0).split(" ", 3);
        if (start.length < 2 || !"HTTP/1.1".equals(start[0]) || !"200".equals(start[1])) {
            throw new ProximityException("unexpected http status: " + lines.get(0));
        }
        Long length = contentLength(lines);
        if (length == null) {
            throw new ProximityException("http response has no Content-Length");
        }
        if (length < 0 || length > MAX_BODY) {
            throw new ProximityException("http response Content-Length out of range: " + length);
        }
        return readFully(in, (int) (long) length);
    }

    private static int requestLength(@NonNull List<String> lines) {
        boolean chunked = false;
        for (String line : lines.subList(1, lines.size())) {
            if (TRANSFER_ENCODING.equals(headerName(line))) {
                chunked = true;
            }
        }
        Long length;
        try {
            length = contentLength(lines);
        } catch (ProximityException e) {
            throw new RequestRejected(400, e.getMessage());
        }
        if (chunked && length != null) {
            throw new RequestRejected(400, "Transfer-Encoding together with Content-Length");
        }
        if (chunked) {
            throw new RequestRejected(501, "Transfer-Encoding is not supported");
        }
        if (length == null) {
            throw new RequestRejected(411, "Content-Length is required");
        }
        if (length < 0) {
            throw new RequestRejected(400, "negative Content-Length");
        }
        return (int) Math.min(length, (long) MAX_BODY + 1);
    }

    @Nullable
    private static Long contentLength(@NonNull List<String> lines) {
        Long length = null;
        for (String line : lines.subList(1, lines.size())) {
            if (!CONTENT_LENGTH.equals(headerName(line))) {
                continue;
            }
            if (length != null) {
                throw new ProximityException("duplicate Content-Length");
            }
            String value = line.substring(line.indexOf(':') + 1).trim();
            try {
                length = Long.parseLong(value);
            } catch (NumberFormatException e) {
                throw new ProximityException("malformed Content-Length: " + value);
            }
        }
        return length;
    }

    @Nullable
    private static String headerName(@NonNull String line) {
        int colon = line.indexOf(':');
        if (colon < 0) {
            return null;
        }
        return line.substring(0, colon).trim().toLowerCase();
    }

    @Nullable
    private static List<String> readHead(@NonNull InputStream in) throws IOException {
        List<String> lines = new ArrayList<>();
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int total = 0;
        boolean any = false;
        while (true) {
            int b = in.read();
            if (b < 0) {
                if (!any) {
                    return null;
                }
                throw new ProximityException("http head truncated");
            }
            any = true;
            if (++total > MAX_HEADER) {
                throw new ProximityException("http head exceeds " + MAX_HEADER + " bytes");
            }
            if (b == '\n') {
                byte[] raw = line.toByteArray();
                int end = raw.length > 0 && raw[raw.length - 1] == '\r'
                        ? raw.length - 1 : raw.length;
                String text = new String(raw, 0, end, StandardCharsets.ISO_8859_1);
                line.reset();
                if (text.isEmpty()) {
                    if (lines.isEmpty()) {
                        continue;
                    }
                    return lines;
                }
                lines.add(text);
                continue;
            }
            line.write(b);
        }
    }

    @NonNull
    private static byte[] readFully(@NonNull InputStream in, int length) throws IOException {
        byte[] body = new byte[length];
        int offset = 0;
        while (offset < length) {
            int n = in.read(body, offset, length - offset);
            if (n < 0) {
                throw new ProximityException("http body truncated at " + offset + " of "
                        + length + " bytes");
            }
            offset += n;
        }
        return body;
    }
}
