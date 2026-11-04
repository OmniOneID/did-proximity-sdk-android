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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class NdefMessage {

    private final List<NdefRecord> records;

    private NdefMessage(@NonNull List<NdefRecord> records) {
        this.records = Collections.unmodifiableList(records);
    }

    @NonNull
    public static NdefMessage of(@NonNull List<NdefRecord> records) {
        if (records.isEmpty()) {
            throw new ProximityException("an NDEF message needs at least one record");
        }
        return new NdefMessage(new ArrayList<>(records));
    }

    @NonNull
    public static NdefMessage of(@NonNull NdefRecord... records) {
        return of(Arrays.asList(records));
    }

    @NonNull
    public static NdefMessage parse(@NonNull byte[] encoded) {
        if (encoded.length == 0) {
            throw new ProximityException("empty NDEF message");
        }
        List<NdefRecord> parsed = new ArrayList<>();
        int[] cursor = {0};
        boolean[] last = {false};
        while (true) {
            parsed.add(NdefRecord.read(encoded, cursor, parsed.isEmpty(), last));
            if (last[0]) {
                break;
            }
            if (cursor[0] >= encoded.length) {
                throw new ProximityException("NDEF message ended without a record setting ME");
            }
        }
        if (cursor[0] != encoded.length) {
            throw new ProximityException(
                    "trailing bytes after the NDEF message: " + (encoded.length - cursor[0]));
        }
        return new NdefMessage(parsed);
    }

    @NonNull
    public byte[] encode() {
        int total = 0;
        for (NdefRecord record : records) {
            total += record.encodedLength();
        }
        byte[] out = new byte[total];
        int pos = 0;
        for (int i = 0; i < records.size(); i++) {
            pos = records.get(i).writeTo(out, pos, i == 0, i == records.size() - 1);
        }
        return out;
    }

    @NonNull
    public List<NdefRecord> records() {
        return records;
    }

    @Nullable
    public NdefRecord find(short tnf, @NonNull String type) {
        for (NdefRecord record : records) {
            if (record.is(tnf, type)) {
                return record;
            }
        }
        return null;
    }

    @Nullable
    public NdefRecord findById(short tnf, @NonNull String type, @NonNull String id) {
        for (NdefRecord record : records) {
            if (record.is(tnf, type) && record.hasId(id)) {
                return record;
            }
        }
        return null;
    }

    @Nullable
    public NdefRecord findById(@NonNull String id) {
        for (NdefRecord record : records) {
            if (record.hasId(id)) {
                return record;
            }
        }
        return null;
    }
}
