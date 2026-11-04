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
import org.omnione.did.proximity.sdk.core.nfc.ApduChannel;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;

public final class HolderBackedChannel implements ApduChannel {

    public interface Endpoint {
        byte[] processCommandApdu(@NonNull byte[] apdu,
                                  @NonNull NfcHolderTransport.ResponseSink sink);
    }

    private final Endpoint endpoint;
    private final LinkedBlockingQueue<byte[]> deferred = new LinkedBlockingQueue<>();
    private final List<byte[]> commands = new ArrayList<>();
    private final NfcHolderTransport.ResponseSink sink = deferred::add;

    private int maxTransceiveLength = 261;
    private boolean extended;
    private boolean open = true;
    private long waitMillis = 5_000L;
    private UnaryOperator<byte[]> tamper = UnaryOperator.identity();

    public HolderBackedChannel(@NonNull Endpoint endpoint) {
        this.endpoint = endpoint;
    }

    public HolderBackedChannel(@NonNull NfcHolderTransport holder) {
        this(holder::processCommandApdu);
    }

    public HolderBackedChannel maxTransceiveLength(int value) {
        this.maxTransceiveLength = value;
        return this;
    }

    public HolderBackedChannel extendedLength(boolean value) {
        this.extended = value;
        return this;
    }

    public HolderBackedChannel waitMillis(long value) {
        this.waitMillis = value;
        return this;
    }

    public HolderBackedChannel tamperingResponses(@NonNull UnaryOperator<byte[]> tamper) {
        this.tamper = tamper;
        return this;
    }

    public List<byte[]> commands() {
        return commands;
    }

    public NfcHolderTransport.ResponseSink sink() {
        return sink;
    }

    public void dropLink() {
        open = false;
        deferred.add(new byte[0]);
    }

    @NonNull
    @Override
    public synchronized byte[] transceive(@NonNull byte[] commandApdu) {
        if (!open) {
            throw new ProximityException("tag lost");
        }
        commands.add(commandApdu);
        byte[] response = endpoint.processCommandApdu(commandApdu, sink);
        if (response == null) {
            try {
                response = deferred.poll(waitMillis, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ProximityException("interrupted", e);
            }
            if (response == null) {
                throw new ProximityException("holder never answered the deferred ENVELOPE");
            }
        }
        if (!open || response.length == 0) {
            throw new ProximityException("tag lost");
        }
        return tamper.apply(response);
    }

    @Override
    public int maxTransceiveLength() {
        return maxTransceiveLength;
    }

    @Override
    public boolean extendedLengthSupported() {
        return extended;
    }

    @Override
    public boolean isLinkOpen() {
        return open;
    }

    @Override
    public void close() {
        open = false;
        deferred.add(new byte[0]);
    }
}
