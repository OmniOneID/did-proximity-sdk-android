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
import androidx.annotation.Nullable;

import com.upokecenter.cbor.CBORObject;
import com.upokecenter.cbor.CBORType;

import org.omnione.did.proximity.sdk.core.ProximityException;

import java.util.Arrays;
import java.util.Objects;

public final class WifiOptions {

    private static final int KEY_PASSPHRASE = 0;
    private static final int KEY_OPERATING_CLASS = 1;
    private static final int KEY_CHANNEL_NUMBER = 2;
    private static final int KEY_SUPPORTED_BANDS = 3;

    private final String passphrase;
    private final Integer operatingClass;
    private final Integer channelNumber;
    private final byte[] supportedBands;

    private WifiOptions(@Nullable String passphrase,
                        @Nullable Integer operatingClass,
                        @Nullable Integer channelNumber,
                        @NonNull byte[] supportedBands) {
        this.passphrase = passphrase;
        this.operatingClass = operatingClass;
        this.channelNumber = channelNumber;
        this.supportedBands = supportedBands;
    }

    @NonNull
    public static WifiOptions of(@NonNull byte[] supportedBands) {
        if (supportedBands.length == 0) {
            throw new ProximityException("WifiOptions needs at least one supported band byte");
        }
        return new WifiOptions(null, null, null, supportedBands.clone());
    }

    @NonNull
    public WifiOptions withPassphrase(@NonNull String passphrase) {
        return new WifiOptions(passphrase, operatingClass, channelNumber, supportedBands);
    }

    @NonNull
    public WifiOptions withChannel(int operatingClass, int channelNumber) {
        if (operatingClass < 0 || channelNumber < 0) {
            throw new ProximityException("channel info must be unsigned");
        }
        return new WifiOptions(passphrase, operatingClass, channelNumber, supportedBands);
    }

    @Nullable
    public String passphrase() {
        return passphrase;
    }

    @Nullable
    public Integer operatingClass() {
        return operatingClass;
    }

    @Nullable
    public Integer channelNumber() {
        return channelNumber;
    }

    @NonNull
    public byte[] supportedBands() {
        return supportedBands.clone();
    }

    @NonNull
    public static WifiOptions parse(@NonNull CBORObject options) {
        if (options.getType() != CBORType.Map) {
            throw new ProximityException("WifiOptions is malformed");
        }
        String passphrase = textAt(options, KEY_PASSPHRASE);
        Integer operatingClass = uintAt(options, KEY_OPERATING_CLASS);
        Integer channelNumber = uintAt(options, KEY_CHANNEL_NUMBER);
        byte[] bands = new byte[0];
        CBORObject bandsValue = options.get(KEY_SUPPORTED_BANDS);
        if (bandsValue != null) {
            if (bandsValue.getType() != CBORType.ByteString) {
                throw new ProximityException("WifiOptions supported bands must be a byte string");
            }
            bands = bandsValue.GetByteString();
            if (bands.length == 0) {
                throw new ProximityException("WifiOptions supported bands must not be empty");
            }
        }
        return new WifiOptions(passphrase, operatingClass, channelNumber, bands);
    }

    @Nullable
    private static String textAt(@NonNull CBORObject options, int key) {
        CBORObject value = options.get(key);
        if (value == null) {
            return null;
        }
        if (value.getType() != CBORType.TextString) {
            throw new ProximityException("WifiOptions key " + key + " must be a text string");
        }
        return value.AsString();
    }

    @Nullable
    private static Integer uintAt(@NonNull CBORObject options, int key) {
        CBORObject value = options.get(key);
        if (value == null) {
            return null;
        }
        if (value.getType() != CBORType.Integer || !value.CanValueFitInInt32()
                || value.AsInt32Value() < 0) {
            throw new ProximityException("WifiOptions key " + key + " must be an unsigned integer");
        }
        return value.AsInt32Value();
    }

    @NonNull
    public CBORObject toCbor() {
        CBORObject options = CBORObject.NewMap();
        if (passphrase != null) {
            options.Add(KEY_PASSPHRASE, passphrase);
        }
        if (operatingClass != null) {
            options.Add(KEY_OPERATING_CLASS, operatingClass);
        }
        if (channelNumber != null) {
            options.Add(KEY_CHANNEL_NUMBER, channelNumber);
        }
        options.Add(KEY_SUPPORTED_BANDS, supportedBands);
        return options;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof WifiOptions)) {
            return false;
        }
        WifiOptions that = (WifiOptions) other;
        return Objects.equals(passphrase, that.passphrase)
                && Objects.equals(operatingClass, that.operatingClass)
                && Objects.equals(channelNumber, that.channelNumber)
                && Arrays.equals(supportedBands, that.supportedBands);
    }

    @Override
    public int hashCode() {
        return 31 * Objects.hash(passphrase, operatingClass, channelNumber)
                + Arrays.hashCode(supportedBands);
    }
}
