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
import org.omnione.did.proximity.sdk.core.engagement.NfcOptions;
import org.omnione.did.proximity.sdk.core.logger.ProximityLogger;

import java.io.ByteArrayOutputStream;

public final class NfcCarrier {

    public static final String RECORD_TYPE = "iso.org:18013:nfc";
    public static final String ID = "nfc";
    public static final int VERSION = 0x01;
    public static final int TYPE_MAX_COMMAND = 0x01;
    public static final int TYPE_MAX_RESPONSE = 0x02;

    private final NfcOptions options;

    private NfcCarrier(@Nullable NfcOptions options) {
        this.options = options;
    }

    @Nullable
    public static NfcCarrier of(@Nullable NfcOptions options) {
        return options == null ? null : new NfcCarrier(options);
    }

    @NonNull
    public static NfcCarrier readerCarrier(@NonNull NfcOptions options) {
        return new NfcCarrier(options);
    }

    @Nullable
    public NfcOptions options() {
        return options;
    }

    @NonNull
    public byte[] encode() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(VERSION);
        if (options != null) {
            writeField(out, TYPE_MAX_COMMAND, options.maxCommandLength());
            writeField(out, TYPE_MAX_RESPONSE, options.maxResponseLength());
        }
        return out.toByteArray();
    }

    private static void writeField(@NonNull ByteArrayOutputStream out, int type, int value) {
        byte[] encoded = minimalBigEndian(value);
        out.write(1 + encoded.length);
        out.write(type);
        out.write(encoded, 0, encoded.length);
    }

    @NonNull
    private static byte[] minimalBigEndian(int value) {
        int length = 1;
        while (length < 4 && (value >>> (8 * length)) != 0) {
            length++;
        }
        byte[] out = new byte[length];
        for (int i = 0; i < length; i++) {
            out[length - 1 - i] = (byte) (value >>> (8 * i));
        }
        return out;
    }

    @NonNull
    public static NfcCarrier parse(@NonNull byte[] payload) {
        if (payload.length < 1) {
            throw new ProximityException("empty NFC carrier configuration record");
        }
        int version = payload[0] & 0xFF;
        if (version != VERSION) {
            throw new ProximityException("unsupported NFC carrier version " + version);
        }
        Integer maxCommand = null;
        Integer maxResponse = null;
        int pos = 1;
        while (pos < payload.length) {
            int length = payload[pos] & 0xFF;
            if (length < 1 || pos + 1 + length > payload.length) {
                throw new ProximityException("malformed NFC carrier configuration record");
            }
            int type = payload[pos + 1] & 0xFF;
            int valueLength = length - 1;
            if (type == TYPE_MAX_COMMAND) {
                maxCommand = readUnsigned(payload, pos + 2, valueLength);
            } else if (type == TYPE_MAX_RESPONSE) {
                maxResponse = readUnsigned(payload, pos + 2, valueLength);
            }
            pos += 1 + length;
        }
        if (maxCommand == null || maxResponse == null) {
            return new NfcCarrier(null);
        }
        if (!NfcOptions.inRange(maxCommand, maxResponse)) {
            ProximityLogger.getInstance().d("nfc: ignoring NFC carrier limits out of range"
                    + " maxCmd=" + maxCommand + " maxResp=" + maxResponse);
            return new NfcCarrier(null);
        }
        return new NfcCarrier(NfcOptions.of(maxCommand, maxResponse));
    }

    @Nullable
    private static Integer readUnsigned(@NonNull byte[] source, int offset, int length) {
        if (length < 1 || length > 4) {
            return null;
        }
        long value = 0;
        for (int i = 0; i < length; i++) {
            value = (value << 8) | (source[offset + i] & 0xFF);
        }
        return value > Integer.MAX_VALUE ? null : (int) value;
    }
}
