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
import org.omnione.did.proximity.sdk.core.engagement.NfcOptions;
import org.omnione.did.proximity.sdk.core.logger.ProximityLogger;
import org.omnione.did.proximity.sdk.core.nfc.Apdu;
import org.omnione.did.proximity.sdk.core.nfc.ApduChannel;
import org.omnione.did.proximity.sdk.core.transport.TerminationFrames;
import org.omnione.did.proximity.sdk.core.transport.Transport;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

public final class NfcReaderTransport implements Transport {

    public static final String IO_THREAD = "proximity-nfc-io";
    public static final String CLOSE_THREAD = "proximity-nfc-close";

    private final ApduChannel channel;
    private final boolean extended;
    private final int chunkLength;
    private final int le;
    private final ExecutorService io = Executors.newSingleThreadExecutor(
            runnable -> new Thread(runnable, IO_THREAD));

    private volatile Listener listener;
    private volatile boolean stopped;

    public NfcReaderTransport(@NonNull ApduChannel channel, @NonNull NfcOptions holderLimits) {
        this.channel = channel;
        this.extended = channel.extendedLengthSupported();
        int maxTransceive = channel.maxTransceiveLength();
        int chunk = Math.min(holderLimits.maxCommandLength(),
                maxTransceive - NfcDataTransfer.TRANSCEIVE_MARGIN);
        int response = Math.min(holderLimits.maxResponseLength(), maxTransceive - 2);
        if (!extended) {
            chunk = Math.min(chunk, NfcDataTransfer.SHORT_MAX_DATA);
            response = Math.min(response, NfcDataTransfer.SHORT_MAX_LE);
        }
        this.chunkLength = Math.max(1, chunk);
        this.le = Math.max(1, response);
    }

    public boolean extendedLength() {
        return extended;
    }

    public int chunkLength() {
        return chunkLength;
    }

    public int le() {
        return le;
    }

    @Override
    public void start(@NonNull Listener listener) {
        this.listener = listener;
        execute(() -> {
            Apdu.Response response;
            try {
                response = transceive(EnvelopeFraming.selectMdocApplication());
            } catch (ProximityException e) {
                report(e);
                return;
            }
            if (!response.isSuccess()) {
                report(new ProximityException(String.format(
                        "SELECT of the mdoc data transfer application failed with status %04X",
                        response.status)));
                return;
            }
            ProximityLogger.getInstance().d("nfc: select A0000002480400 → 9000");
            if (!stopped) {
                listener.onConnected();
            }
        });
    }

    @Override
    public void send(@NonNull byte[] message) {
        if (stopped) {
            throw new ProximityException("the NFC transport is stopped");
        }
        execute(() -> exchange(message));
    }

    @Override
    public void stop() {
        if (stopped) {
            return;
        }
        stopped = true;
        io.shutdownNow();
        Thread closer = new Thread(() -> {
            try {
                channel.close();
            } catch (RuntimeException ignoredWhileStopping) {
            }
        }, CLOSE_THREAD);
        closer.setDaemon(true);
        closer.start();
    }

    private void execute(@NonNull Runnable task) {
        try {
            io.execute(task);
        } catch (RejectedExecutionException e) {
            throw new ProximityException("the NFC transport is stopped", e);
        }
    }

    private void exchange(@NonNull byte[] message) {
        boolean termination = TerminationFrames.isTermination(message);
        byte[] payload;
        try {
            byte[] frame = EnvelopeFraming.encodeDo53(message);
            int chunks = (frame.length + chunkLength - 1) / chunkLength;
            Apdu.Response last = null;
            for (int i = 0; i < chunks; i++) {
                int from = i * chunkLength;
                int to = Math.min(frame.length, from + chunkLength);
                boolean isLast = i == chunks - 1;
                ProximityLogger.getInstance().d("envelope chain " + (i + 1) + "/" + chunks);
                Apdu.Response response = transceive(EnvelopeFraming.envelope(
                        isLast, Arrays.copyOfRange(frame, from, to), le, extended));
                if (!isLast && !response.isSuccess()) {
                    throw new ProximityException(String.format(
                            "ENVELOPE %d/%d failed with status %04X", i + 1, chunks,
                            response.status));
                }
                last = response;
            }
            if (last == null) {
                throw new ProximityException("no ENVELOPE response");
            }
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            body.write(last.data, 0, last.data.length);
            while (EnvelopeFraming.isMoreData(last.status)) {
                ProximityLogger.getInstance().d("get response");
                last = transceive(EnvelopeFraming.getResponse(le, extended));
                body.write(last.data, 0, last.data.length);
            }
            if (!last.isSuccess()) {
                throw new ProximityException(String.format(
                        "the mdoc answered the ENVELOPE with status %04X", last.status));
            }
            payload = EnvelopeFraming.decodeDo53(body.toByteArray());
        } catch (ProximityException e) {
            report(e);
            return;
        }
        if (stopped) {
            return;
        }
        if (payload == null) {
            if (!termination) {
                listener.onDisconnected();
            }
            return;
        }
        listener.onMessage(payload);
    }

    @NonNull
    private Apdu.Response transceive(@NonNull byte[] command) {
        ProximityLogger logger = ProximityLogger.getInstance();
        logger.v("nfc > " + Apdu.hex(command));
        byte[] raw;
        try {
            raw = channel.transceive(command);
        } catch (RuntimeException e) {
            throw new ProximityException("NFC link lost during data retrieval", e);
        }
        logger.v("nfc < " + Apdu.hex(raw));
        return Apdu.parseResponse(raw);
    }

    private void report(@NonNull ProximityException error) {
        if (stopped) {
            return;
        }
        listener.onError(error);
    }
}
