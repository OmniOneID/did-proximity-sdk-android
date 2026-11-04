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

package org.omnione.did.proximity.sdk.reader.nfc;

import android.nfc.tech.IsoDep;

import androidx.annotation.NonNull;

import org.omnione.did.proximity.sdk.core.ProximityException;
import org.omnione.did.proximity.sdk.core.nfc.ApduChannel;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

final class IsoDepApduChannel implements ApduChannel {

    private final IsoDep isoDep;
    private final AtomicBoolean closed = new AtomicBoolean();

    IsoDepApduChannel(@NonNull IsoDep isoDep) {
        this.isoDep = isoDep;
    }

    @NonNull
    @Override
    public byte[] transceive(@NonNull byte[] commandApdu) {
        try {
            byte[] response = isoDep.transceive(commandApdu);
            if (response == null || response.length == 0) {
                throw new ProximityException("the tag answered with an empty response APDU;"
                        + " the NFC link dropped or the reader was not routed to an application");
            }
            return response;
        } catch (IOException e) {
            throw new ProximityException("I/O failure on the NFC link", e);
        }
    }

    @Override
    public int maxTransceiveLength() {
        return isoDep.getMaxTransceiveLength();
    }

    @Override
    public boolean extendedLengthSupported() {
        return isoDep.isExtendedLengthApduSupported();
    }

    @Override
    public boolean isLinkOpen() {
        return !closed.get();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        try {
            isoDep.close();
        } catch (IOException ignored) {
        }
    }
}
