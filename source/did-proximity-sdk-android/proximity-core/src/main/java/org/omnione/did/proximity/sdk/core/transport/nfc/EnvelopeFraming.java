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

package org.omnione.did.proximity.sdk.core.transport.nfc;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.omnione.did.proximity.sdk.core.ProximityException;
import org.omnione.did.proximity.sdk.core.nfc.Type4Tag;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

public final class EnvelopeFraming {

    private EnvelopeFraming() {
    }

    @NonNull
    public static byte[] encodeDo53(@NonNull byte[] data) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(data.length + 5);
        out.write(NfcDataTransfer.TAG_DO53);
        int length = data.length;
        if (length < 0x80) {
            out.write(length);
        } else if (length < 0x100) {
            out.write(0x81);
            out.write(length);
        } else if (length < 0x10000) {
            out.write(0x82);
            out.write(length >>> 8);
            out.write(length);
        } else if (length < 0x1000000) {
            out.write(0x83);
            out.write(length >>> 16);
            out.write(length >>> 8);
            out.write(length);
        } else {
            throw new ProximityException("DO'53' payload too large: " + length);
        }
        out.write(data, 0, length);
        return out.toByteArray();
    }

    @Nullable
    public static byte[] decodeDo53(@NonNull byte[] encoded) {
        if (encoded.length == 0) {
            return null;
        }
        if (encoded.length < 2) {
            throw new ProximityException("DO'53' shorter than a tag and a length");
        }
        if ((encoded[0] & 0xFF) != NfcDataTransfer.TAG_DO53) {
            throw new ProximityException("expected DO'53', got tag "
                    + Integer.toHexString(encoded[0] & 0xFF));
        }
        int first = encoded[1] & 0xFF;
        int length;
        int offset;
        if (first < 0x80) {
            length = first;
            offset = 2;
        } else if (first == 0x81) {
            requireAtLeast(encoded, 3);
            length = encoded[2] & 0xFF;
            offset = 3;
        } else if (first == 0x82) {
            requireAtLeast(encoded, 4);
            length = ((encoded[2] & 0xFF) << 8) | (encoded[3] & 0xFF);
            offset = 4;
        } else if (first == 0x83) {
            requireAtLeast(encoded, 5);
            length = ((encoded[2] & 0xFF) << 16) | ((encoded[3] & 0xFF) << 8)
                    | (encoded[4] & 0xFF);
            offset = 5;
        } else {
            throw new ProximityException("DO'53' length form not supported: "
                    + Integer.toHexString(first));
        }
        if (encoded.length - offset != length) {
            throw new ProximityException("DO'53' declares " + length + " bytes but carries "
                    + (encoded.length - offset));
        }
        return Arrays.copyOfRange(encoded, offset, offset + length);
    }

    private static void requireAtLeast(@NonNull byte[] encoded, int length) {
        if (encoded.length < length) {
            throw new ProximityException("truncated DO'53' length");
        }
    }

    @NonNull
    public static byte[] selectMdocApplication() {
        byte[] aid = NfcDataTransfer.AID_MDOC_DATA;
        ByteArrayOutputStream out = new ByteArrayOutputStream(5 + aid.length);
        out.write(Type4Tag.CLA);
        out.write(Type4Tag.INS_SELECT);
        out.write(Type4Tag.P1_SELECT_APPLICATION);
        out.write(NfcDataTransfer.P2_SELECT_DIS);
        out.write(aid.length);
        out.write(aid, 0, aid.length);
        return out.toByteArray();
    }

    @NonNull
    public static byte[] envelope(boolean last, @NonNull byte[] chunk, int le, boolean extended) {
        if (chunk.length == 0) {
            throw new ProximityException("ENVELOPE carries no data");
        }
        int maxData = extended ? NfcDataTransfer.EXTENDED_MAX_DATA : NfcDataTransfer.SHORT_MAX_DATA;
        if (chunk.length > maxData) {
            throw new ProximityException("ENVELOPE data exceeds " + maxData + ": " + chunk.length);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(chunk.length + 9);
        out.write(last ? NfcDataTransfer.CLA_CHAIN_LAST : NfcDataTransfer.CLA_CHAIN_MORE);
        out.write(NfcDataTransfer.INS_ENVELOPE);
        out.write(0x00);
        out.write(0x00);
        if (extended) {
            out.write(0x00);
            out.write(chunk.length >>> 8);
            out.write(chunk.length);
        } else {
            out.write(chunk.length);
        }
        out.write(chunk, 0, chunk.length);
        if (last) {
            writeLe(out, le, extended);
        }
        return out.toByteArray();
    }

    @NonNull
    public static byte[] getResponse(int le, boolean extended) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(7);
        out.write(NfcDataTransfer.CLA_CHAIN_LAST);
        out.write(NfcDataTransfer.INS_GET_RESPONSE);
        out.write(0x00);
        out.write(0x00);
        if (extended) {
            out.write(0x00);
        }
        writeLe(out, le, extended);
        return out.toByteArray();
    }

    private static void writeLe(@NonNull ByteArrayOutputStream out, int le, boolean extended) {
        if (extended) {
            if (le < 1 || le > NfcDataTransfer.EXTENDED_MAX_LE) {
                throw new ProximityException("Le out of extended range: " + le);
            }
            out.write(le >>> 8);
            out.write(le);
        } else {
            if (le < 1 || le > NfcDataTransfer.SHORT_MAX_LE) {
                throw new ProximityException("Le out of short range: " + le);
            }
            out.write(le);
        }
    }

    public static boolean isMoreData(int status) {
        return (status & 0xFF00) == (NfcDataTransfer.SW_MORE_DATA << 8);
    }
}
