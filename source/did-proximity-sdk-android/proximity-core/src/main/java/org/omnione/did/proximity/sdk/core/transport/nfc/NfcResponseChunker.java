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

import org.omnione.did.proximity.sdk.core.ProximityException;
import org.omnione.did.proximity.sdk.core.nfc.Apdu;
import org.omnione.did.proximity.sdk.core.nfc.Type4Tag;

import java.util.Arrays;

public final class NfcResponseChunker {

    private final byte[] payload;
    private final int le;
    private int offset;
    private int delivered;

    public NfcResponseChunker(@NonNull byte[] payload, int le) {
        if (le < 1) {
            throw new ProximityException("Le must be positive: " + le);
        }
        this.payload = payload;
        this.le = le;
    }

    public boolean hasMore() {
        return offset < payload.length;
    }

    public int remaining() {
        return payload.length - offset;
    }

    public int chunks() {
        return Math.max(1, (payload.length + le - 1) / le);
    }

    public int delivered() {
        return delivered;
    }

    @NonNull
    public Apdu.Response next() {
        int available = payload.length - offset;
        delivered++;
        if (le >= available) {
            byte[] data = Arrays.copyOfRange(payload, offset, payload.length);
            offset = payload.length;
            return new Apdu.Response(data, Type4Tag.SW_SUCCESS);
        }
        byte[] data = Arrays.copyOfRange(payload, offset, offset + le);
        offset += le;
        int remaining = available - le;
        int status = (NfcDataTransfer.SW_MORE_DATA << 8)
                | (remaining <= NfcDataTransfer.SHORT_MAX_DATA ? remaining : 0);
        return new Apdu.Response(data, status);
    }
}
