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

package org.omnione.did.proximity.sdk.core.engagement;

import androidx.annotation.NonNull;

import com.upokecenter.cbor.CBORObject;
import com.upokecenter.cbor.CBORType;

import org.omnione.did.proximity.sdk.core.ProximityException;

import java.util.Objects;

public final class NfcOptions {

    public static final int KEY_MAX_COMMAND = 0;
    public static final int KEY_MAX_RESPONSE = 1;

    public static final int MIN_COMMAND_LENGTH = 255;
    public static final int MAX_COMMAND_LENGTH = 65535;
    public static final int MIN_RESPONSE_LENGTH = 256;
    public static final int MAX_RESPONSE_LENGTH = 65536;

    private final int maxCommandLength;
    private final int maxResponseLength;

    private NfcOptions(int maxCommandLength, int maxResponseLength) {
        this.maxCommandLength = maxCommandLength;
        this.maxResponseLength = maxResponseLength;
    }

    @NonNull
    public static NfcOptions of(int maxCommandLength, int maxResponseLength) {
        if (maxCommandLength < MIN_COMMAND_LENGTH || maxCommandLength > MAX_COMMAND_LENGTH) {
            throw new ProximityException("NfcOptions max command length out of range: "
                    + maxCommandLength);
        }
        if (maxResponseLength < MIN_RESPONSE_LENGTH || maxResponseLength > MAX_RESPONSE_LENGTH) {
            throw new ProximityException("NfcOptions max response length out of range: "
                    + maxResponseLength);
        }
        return new NfcOptions(maxCommandLength, maxResponseLength);
    }

    public static boolean inRange(int maxCommandLength, int maxResponseLength) {
        return maxCommandLength >= MIN_COMMAND_LENGTH && maxCommandLength <= MAX_COMMAND_LENGTH
                && maxResponseLength >= MIN_RESPONSE_LENGTH
                && maxResponseLength <= MAX_RESPONSE_LENGTH;
    }

    public int maxCommandLength() {
        return maxCommandLength;
    }

    public int maxResponseLength() {
        return maxResponseLength;
    }

    @NonNull
    public static NfcOptions parse(@NonNull CBORObject options) {
        if (options.getType() != CBORType.Map) {
            throw new ProximityException("NfcOptions is malformed");
        }
        return of(uintAt(options, KEY_MAX_COMMAND), uintAt(options, KEY_MAX_RESPONSE));
    }

    private static int uintAt(@NonNull CBORObject options, int key) {
        CBORObject value = options.get(key);
        if (value == null) {
            throw new ProximityException("NfcOptions is missing key " + key);
        }
        if (value.getType() != CBORType.Integer || !value.CanValueFitInInt32()
                || value.AsInt32Value() < 0) {
            throw new ProximityException("NfcOptions key " + key + " must be an unsigned integer");
        }
        return value.AsInt32Value();
    }

    @NonNull
    public CBORObject toCbor() {
        CBORObject options = CBORObject.NewMap();
        options.Add(KEY_MAX_COMMAND, maxCommandLength);
        options.Add(KEY_MAX_RESPONSE, maxResponseLength);
        return options;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof NfcOptions)) {
            return false;
        }
        NfcOptions that = (NfcOptions) other;
        return maxCommandLength == that.maxCommandLength
                && maxResponseLength == that.maxResponseLength;
    }

    @Override
    public int hashCode() {
        return Objects.hash(maxCommandLength, maxResponseLength);
    }

    @NonNull
    @Override
    public String toString() {
        return "NfcOptions{maxCmd=" + maxCommandLength + ", maxResp=" + maxResponseLength + "}";
    }
}
