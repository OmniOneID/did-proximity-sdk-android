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
package org.omnione.did.proximity.sdk.core.transport.ble;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.omnione.did.proximity.sdk.core.ProximityException;

import java.io.IOException;
import java.io.InputStream;

public final class L2capFraming {

    public static final int HEADER_LENGTH = 4;
    public static final int MAX_FRAME = 1 << 20;
    public static final int PSM_LENGTH = 4;
    public static final int MIN_PSM = 1;
    public static final int MAX_PSM = 0xFFFF;

    private L2capFraming() {}

    @NonNull
    public static byte[] frame(@NonNull byte[] body) {
        if (body.length > MAX_FRAME) {
            throw new ProximityException("l2cap frame of " + body.length + " bytes exceeds "
                    + MAX_FRAME);
        }
        byte[] out = new byte[HEADER_LENGTH + body.length];
        writeUInt32(out, 0, body.length);
        System.arraycopy(body, 0, out, HEADER_LENGTH, body.length);
        return out;
    }

    @Nullable
    public static byte[] readFrame(@NonNull InputStream in) throws IOException {
        byte[] header = new byte[HEADER_LENGTH];
        int first = in.read();
        if (first < 0) {
            return null;
        }
        header[0] = (byte) first;
        readFully(in, header, 1, "l2cap frame header");
        long length = readUInt32(header, 0);
        if (length > MAX_FRAME) {
            throw new ProximityException("l2cap frame of " + length + " bytes exceeds "
                    + MAX_FRAME);
        }
        byte[] body = new byte[(int) length];
        readFully(in, body, 0, "l2cap frame body");
        return body;
    }

    @NonNull
    public static byte[] encodePsm(int psm) {
        requirePsm(psm);
        byte[] out = new byte[PSM_LENGTH];
        writeUInt32(out, 0, psm);
        return out;
    }

    public static int decodePsm(@NonNull byte[] value) {
        if (value.length != PSM_LENGTH) {
            throw new ProximityException("l2cap psm value must be " + PSM_LENGTH
                    + " bytes, got " + value.length);
        }
        long psm = readUInt32(value, 0);
        if (psm < MIN_PSM || psm > MAX_PSM) {
            throw new ProximityException("l2cap psm out of range: " + psm);
        }
        return (int) psm;
    }

    public static int requirePsm(int psm) {
        if (psm < MIN_PSM || psm > MAX_PSM) {
            throw new ProximityException("l2cap psm out of range: " + psm);
        }
        return psm;
    }

    private static void readFully(@NonNull InputStream in, @NonNull byte[] target,
                                  int offset, @NonNull String what) throws IOException {
        while (offset < target.length) {
            int n = in.read(target, offset, target.length - offset);
            if (n < 0) {
                throw new ProximityException(what + " truncated at " + offset + " of "
                        + target.length + " bytes");
            }
            offset += n;
        }
    }

    private static void writeUInt32(@NonNull byte[] target, int offset, int value) {
        target[offset] = (byte) (value >>> 24);
        target[offset + 1] = (byte) (value >>> 16);
        target[offset + 2] = (byte) (value >>> 8);
        target[offset + 3] = (byte) value;
    }

    private static long readUInt32(@NonNull byte[] source, int offset) {
        return ((long) (source[offset] & 0xFF) << 24)
                | ((long) (source[offset + 1] & 0xFF) << 16)
                | ((long) (source[offset + 2] & 0xFF) << 8)
                | (long) (source[offset + 3] & 0xFF);
    }
}
