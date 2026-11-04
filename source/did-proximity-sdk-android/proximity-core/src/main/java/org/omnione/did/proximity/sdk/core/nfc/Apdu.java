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

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

public final class Apdu {

    public static final byte[] EMPTY = new byte[0];

    private Apdu() {
    }

    public static final class Command {
        public final int cla;
        public final int ins;
        public final int p1;
        public final int p2;
        @NonNull public final byte[] data;
        public final int le;

        public Command(int cla, int ins, int p1, int p2, @NonNull byte[] data, int le) {
            this.cla = cla;
            this.ins = ins;
            this.p1 = p1;
            this.p2 = p2;
            this.data = data;
            this.le = le;
        }

        int offset() {
            return (p1 << 8) | p2;
        }
    }

    public static final class Response {
        @NonNull public final byte[] data;
        public final int status;

        public Response(@NonNull byte[] data, int status) {
            this.data = data;
            this.status = status;
        }

        public boolean isSuccess() {
            return status == Type4Tag.SW_SUCCESS;
        }
    }

    @NonNull
    static byte[] selectApplication(@NonNull byte[] aid) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(Type4Tag.CLA);
        out.write(Type4Tag.INS_SELECT);
        out.write(Type4Tag.P1_SELECT_APPLICATION);
        out.write(Type4Tag.P2_SELECT_APPLICATION);
        out.write(aid.length);
        out.write(aid, 0, aid.length);
        out.write(0x00);
        return out.toByteArray();
    }

    @NonNull
    static byte[] selectFile(int fileId) {
        return new byte[]{
                (byte) Type4Tag.CLA, (byte) Type4Tag.INS_SELECT,
                (byte) Type4Tag.P1_SELECT_FILE, (byte) Type4Tag.P2_SELECT_FILE,
                0x02, (byte) (fileId >>> 8), (byte) fileId
        };
    }

    @NonNull
    static byte[] readBinary(int offset, int length) {
        if (length < 1 || length > 255) {
            throw new ProximityException("READ BINARY length out of short-form range: " + length);
        }
        return new byte[]{
                (byte) Type4Tag.CLA, (byte) Type4Tag.INS_READ_BINARY,
                (byte) (offset >>> 8), (byte) offset, (byte) length
        };
    }

    @NonNull
    static byte[] updateBinary(int offset, @NonNull byte[] data) {
        if (data.length < 1 || data.length > 255) {
            throw new ProximityException("UPDATE BINARY length out of short-form range: "
                    + data.length);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(Type4Tag.CLA);
        out.write(Type4Tag.INS_UPDATE_BINARY);
        out.write(offset >>> 8);
        out.write(offset);
        out.write(data.length);
        out.write(data, 0, data.length);
        return out.toByteArray();
    }

    @NonNull
    public static Response parseResponse(@NonNull byte[] response) {
        if (response.length < 2) {
            throw new ProximityException("response APDU shorter than a status word");
        }
        int status = ((response[response.length - 2] & 0xFF) << 8)
                | (response[response.length - 1] & 0xFF);
        return new Response(Arrays.copyOf(response, response.length - 2), status);
    }

    @NonNull
    public static byte[] response(@Nullable byte[] data, int status) {
        int length = (data == null ? 0 : data.length) + 2;
        byte[] out = new byte[length];
        if (data != null) {
            System.arraycopy(data, 0, out, 0, data.length);
        }
        out[length - 2] = (byte) (status >>> 8);
        out[length - 1] = (byte) status;
        return out;
    }

    @NonNull
    public static Command parseCommand(@NonNull byte[] apdu) {
        if (apdu.length < 4) {
            throw new ProximityException("command APDU shorter than a header");
        }
        int cla = apdu[0] & 0xFF;
        int ins = apdu[1] & 0xFF;
        int p1 = apdu[2] & 0xFF;
        int p2 = apdu[3] & 0xFF;

        if (apdu.length == 4) {
            return new Command(cla, ins, p1, p2, EMPTY, 0);
        }
        if (apdu.length == 5) {
            int le = apdu[4] & 0xFF;
            return new Command(cla, ins, p1, p2, EMPTY, le == 0 ? 256 : le);
        }
        int lc = apdu[4] & 0xFF;
        if (lc == 0) {
            return parseExtended(apdu, cla, ins, p1, p2);
        }
        if (apdu.length < 5 + lc) {
            throw new ProximityException("truncated command APDU");
        }
        byte[] data = Arrays.copyOfRange(apdu, 5, 5 + lc);
        int le = 0;
        if (apdu.length == 6 + lc) {
            int encoded = apdu[5 + lc] & 0xFF;
            le = encoded == 0 ? 256 : encoded;
        } else if (apdu.length != 5 + lc) {
            throw new ProximityException("malformed command APDU");
        }
        return new Command(cla, ins, p1, p2, data, le);
    }

    @NonNull
    private static Command parseExtended(@NonNull byte[] apdu,
                                         int cla, int ins, int p1, int p2) {
        if (apdu.length == 7) {
            int le = ((apdu[5] & 0xFF) << 8) | (apdu[6] & 0xFF);
            return new Command(cla, ins, p1, p2, EMPTY, le == 0 ? 65536 : le);
        }
        if (apdu.length < 7) {
            throw new ProximityException("truncated extended length command APDU");
        }
        int lc = ((apdu[5] & 0xFF) << 8) | (apdu[6] & 0xFF);
        if (lc == 0) {
            throw new ProximityException("extended length command APDU declares no data");
        }
        if (apdu.length < 7 + lc) {
            throw new ProximityException("truncated extended length command APDU");
        }
        byte[] data = Arrays.copyOfRange(apdu, 7, 7 + lc);
        int le = 0;
        if (apdu.length == 9 + lc) {
            int encoded = ((apdu[7 + lc] & 0xFF) << 8) | (apdu[8 + lc] & 0xFF);
            le = encoded == 0 ? 65536 : encoded;
        } else if (apdu.length != 7 + lc) {
            throw new ProximityException("malformed extended length command APDU");
        }
        return new Command(cla, ins, p1, p2, data, le);
    }

    @NonNull
    public static String hex(@NonNull byte[] value) {
        StringBuilder text = new StringBuilder(value.length * 2);
        for (byte b : value) {
            text.append(Character.forDigit((b >> 4) & 0x0F, 16));
            text.append(Character.forDigit(b & 0x0F, 16));
        }
        return text.toString();
    }
}
