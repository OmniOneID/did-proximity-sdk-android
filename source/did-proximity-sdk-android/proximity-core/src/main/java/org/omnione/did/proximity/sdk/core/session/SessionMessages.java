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

package org.omnione.did.proximity.sdk.core.session;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.upokecenter.cbor.CBORException;
import com.upokecenter.cbor.CBORObject;
import com.upokecenter.cbor.CBORType;

import org.omnione.did.proximity.sdk.core.ProximityException;
import org.omnione.did.proximity.sdk.core.cbor.CborTags;

public final class SessionMessages {

    private static final String KEY_E_READER_KEY = "eReaderKey";
    private static final String KEY_DATA = "data";
    private static final String KEY_STATUS = "status";

    public static final int STATUS_SESSION_TERMINATION = 20;
    public static final int STATUS_SESSION_ENCRYPTION_ERROR = 10;
    public static final int STATUS_CBOR_DECODING_ERROR = 11;

    private SessionMessages() {}

    public static final class Establishment {
        public final byte[] eReaderKeyBytes;
        public final byte[] data;

        Establishment(@NonNull byte[] eReaderKeyBytes, @NonNull byte[] data) {
            this.eReaderKeyBytes = eReaderKeyBytes;
            this.data = data;
        }
    }

    @NonNull
    public static Establishment parseEstablishment(@NonNull byte[] encoded) {
        CBORObject message = decode(encoded);
        CBORObject readerKey = message.get(KEY_E_READER_KEY);
        CBORObject data = message.get(KEY_DATA);
        if (readerKey == null || data == null) {
            throw new ProximityException("SessionEstablishment is missing eReaderKey or data");
        }
        return new Establishment(CborTags.untag(readerKey), byteString(data, KEY_DATA));
    }

    @NonNull
    public static byte[] data(@NonNull byte[] encrypted) {
        CBORObject message = CBORObject.NewMap();
        message.Add(KEY_DATA, encrypted);
        return message.EncodeToBytes();
    }

    @Nullable
    public static byte[] parseData(@NonNull byte[] encoded) {
        CBORObject data = decode(encoded).get(KEY_DATA);
        return data == null ? null : byteString(data, KEY_DATA);
    }

    @Nullable
    public static Integer parseStatus(@NonNull byte[] encoded) {
        CBORObject status = decode(encoded).get(KEY_STATUS);
        if (status == null) {
            return null;
        }
        if (status.getType() != CBORType.Integer || status.isTagged()) {
            throw new ProximityException("expected status uint, got " + status.getType());
        }
        if (!status.CanValueFitInInt32()) {
            throw new ProximityException("status is outside the supported uint range");
        }
        int value = status.AsInt32Value();
        if (value < 0) {
            throw new ProximityException("expected status uint, got " + value);
        }
        return value;
    }

    @NonNull
    public static byte[] establishment(@NonNull byte[] eReaderKeyBytes,
                                       @NonNull byte[] encrypted) {
        CBORObject message = CBORObject.NewMap();
        message.Add(KEY_E_READER_KEY, CborTags.tagged(eReaderKeyBytes));
        message.Add(KEY_DATA, encrypted);
        return message.EncodeToBytes();
    }

    @NonNull
    public static byte[] status(int status) {
        if (status < 0) {
            throw new ProximityException("status must be a uint, was " + status);
        }
        CBORObject message = CBORObject.NewMap();
        message.Add(KEY_STATUS, status);
        return message.EncodeToBytes();
    }

    @NonNull
    public static byte[] termination() {
        return status(STATUS_SESSION_TERMINATION);
    }

    @NonNull
    private static CBORObject decode(@NonNull byte[] encoded) {
        CBORObject message;
        try {
            message = CBORObject.DecodeFromBytes(encoded);
        } catch (CBORException e) {
            throw new ProximityException("malformed session message", e);
        }
        if (message.getType() != CBORType.Map) {
            throw new ProximityException("expected session message map, got " + message.getType());
        }
        return message;
    }

    @NonNull
    private static byte[] byteString(@NonNull CBORObject value, @NonNull String field) {
        if (value.getType() != CBORType.ByteString) {
            throw new ProximityException("expected " + field + " byte string, got " + value.getType());
        }
        return value.GetByteString();
    }
}
