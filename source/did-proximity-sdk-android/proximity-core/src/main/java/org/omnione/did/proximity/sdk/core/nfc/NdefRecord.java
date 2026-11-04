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

package org.omnione.did.proximity.sdk.core.nfc;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.omnione.did.proximity.sdk.core.ProximityException;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public final class NdefRecord {

    public static final short TNF_EMPTY = 0x00;
    public static final short TNF_WELL_KNOWN = 0x01;
    public static final short TNF_MIME_MEDIA = 0x02;
    public static final short TNF_ABSOLUTE_URI = 0x03;
    public static final short TNF_EXTERNAL_TYPE = 0x04;
    public static final short TNF_UNKNOWN = 0x05;

    static final int FLAG_MB = 0x80;
    static final int FLAG_ME = 0x40;
    static final int FLAG_CF = 0x20;
    static final int FLAG_SR = 0x10;
    static final int FLAG_IL = 0x08;
    static final int MASK_TNF = 0x07;

    static final int SHORT_RECORD_LIMIT = 256;

    private final short tnf;
    private final byte[] type;
    private final byte[] id;
    private final byte[] payload;

    private NdefRecord(short tnf, @NonNull byte[] type, @Nullable byte[] id, @NonNull byte[] payload) {
        this.tnf = tnf;
        this.type = type;
        this.id = id;
        this.payload = payload;
    }

    @NonNull
    public static NdefRecord of(short tnf, @NonNull byte[] type, @Nullable byte[] id, @NonNull byte[] payload) {
        if (tnf < TNF_EMPTY || tnf > TNF_UNKNOWN) {
            throw new ProximityException("unsupported NDEF TNF " + tnf);
        }
        if (type.length > 255) {
            throw new ProximityException("NDEF type longer than 255 bytes: " + type.length);
        }
        byte[] normalisedId = (id == null || id.length == 0) ? null : id.clone();
        if (normalisedId != null && normalisedId.length > 255) {
            throw new ProximityException("NDEF id longer than 255 bytes: " + normalisedId.length);
        }
        return new NdefRecord(tnf, type.clone(), normalisedId, payload.clone());
    }

    @NonNull
    public static NdefRecord of(short tnf, @NonNull String type, @Nullable String id, @NonNull byte[] payload) {
        return of(tnf,
                type.getBytes(StandardCharsets.UTF_8),
                id == null ? null : id.getBytes(StandardCharsets.UTF_8),
                payload);
    }

    public short tnf() {
        return tnf;
    }

    @NonNull
    public byte[] type() {
        return type.clone();
    }

    @Nullable
    public byte[] id() {
        return id == null ? null : id.clone();
    }

    @NonNull
    public byte[] payload() {
        return payload.clone();
    }

    public boolean is(short expectedTnf, @NonNull String expectedType) {
        return tnf == expectedTnf && Arrays.equals(type, expectedType.getBytes(StandardCharsets.UTF_8));
    }

    public boolean hasId(@NonNull String expectedId) {
        return id != null && Arrays.equals(id, expectedId.getBytes(StandardCharsets.UTF_8));
    }

    int encodedLength() {
        boolean shortRecord = payload.length < SHORT_RECORD_LIMIT;
        return 2 + (shortRecord ? 1 : 4) + (id == null ? 0 : 1) + type.length
                + (id == null ? 0 : id.length) + payload.length;
    }

    int writeTo(@NonNull byte[] out, int offset, boolean first, boolean last) {
        boolean shortRecord = payload.length < SHORT_RECORD_LIMIT;
        int header = tnf & MASK_TNF;
        if (first) {
            header |= FLAG_MB;
        }
        if (last) {
            header |= FLAG_ME;
        }
        if (shortRecord) {
            header |= FLAG_SR;
        }
        if (id != null) {
            header |= FLAG_IL;
        }

        int pos = offset;
        out[pos++] = (byte) header;
        out[pos++] = (byte) type.length;
        if (shortRecord) {
            out[pos++] = (byte) payload.length;
        } else {
            out[pos++] = (byte) (payload.length >>> 24);
            out[pos++] = (byte) (payload.length >>> 16);
            out[pos++] = (byte) (payload.length >>> 8);
            out[pos++] = (byte) payload.length;
        }
        if (id != null) {
            out[pos++] = (byte) id.length;
        }
        System.arraycopy(type, 0, out, pos, type.length);
        pos += type.length;
        if (id != null) {
            System.arraycopy(id, 0, out, pos, id.length);
            pos += id.length;
        }
        System.arraycopy(payload, 0, out, pos, payload.length);
        return pos + payload.length;
    }

    @NonNull
    static NdefRecord read(@NonNull byte[] encoded, @NonNull int[] cursor, boolean expectFirst, boolean[] outLast) {
        int pos = cursor[0];
        int header = readByte(encoded, pos++, "record header");

        if ((header & FLAG_CF) != 0) {
            throw new ProximityException("chunked NDEF records are not supported");
        }
        boolean messageBegin = (header & FLAG_MB) != 0;
        if (messageBegin != expectFirst) {
            throw new ProximityException(expectFirst
                    ? "first NDEF record does not set MB"
                    : "MB set on a record that is not the first");
        }

        short recordTnf = (short) (header & MASK_TNF);
        boolean shortRecord = (header & FLAG_SR) != 0;
        boolean hasIdLength = (header & FLAG_IL) != 0;

        int typeLength = readByte(encoded, pos++, "type length");

        int payloadLength;
        if (shortRecord) {
            payloadLength = readByte(encoded, pos++, "payload length");
        } else {
            payloadLength = (readByte(encoded, pos, "payload length") << 24)
                    | (readByte(encoded, pos + 1, "payload length") << 16)
                    | (readByte(encoded, pos + 2, "payload length") << 8)
                    | readByte(encoded, pos + 3, "payload length");
            pos += 4;
            if (payloadLength < 0) {
                throw new ProximityException("NDEF payload length overflows int");
            }
        }

        int idLength = hasIdLength ? readByte(encoded, pos++, "id length") : 0;

        byte[] recordType = slice(encoded, pos, typeLength, "type");
        pos += typeLength;
        byte[] recordId = hasIdLength ? slice(encoded, pos, idLength, "id") : null;
        pos += idLength;
        byte[] recordPayload = slice(encoded, pos, payloadLength, "payload");
        pos += payloadLength;

        cursor[0] = pos;
        outLast[0] = (header & FLAG_ME) != 0;
        return new NdefRecord(recordTnf, recordType,
                (recordId == null || recordId.length == 0) ? null : recordId, recordPayload);
    }

    private static int readByte(@NonNull byte[] encoded, int index, @NonNull String what) {
        if (index >= encoded.length) {
            throw new ProximityException("truncated NDEF message while reading " + what);
        }
        return encoded[index] & 0xFF;
    }

    private static byte[] slice(@NonNull byte[] encoded, int offset, int length, @NonNull String what) {
        if (length < 0 || offset + length > encoded.length) {
            throw new ProximityException("truncated NDEF message while reading " + what);
        }
        return Arrays.copyOfRange(encoded, offset, offset + length);
    }
}
