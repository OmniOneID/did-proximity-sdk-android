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
import org.omnione.did.proximity.sdk.core.engagement.NfcOptions;
import org.omnione.did.proximity.sdk.core.logger.ProximityLogger;
import org.omnione.did.proximity.sdk.core.nfc.Apdu;
import org.omnione.did.proximity.sdk.core.nfc.Type4Tag;
import org.omnione.did.proximity.sdk.core.transport.TerminationFrames;
import org.omnione.did.proximity.sdk.core.transport.Transport;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

public final class NfcHolderTransport implements Transport {

    public interface ResponseSink {
        void sendResponseApdu(@NonNull byte[] responseApdu);
    }

    private final NfcOptions declared;
    private final Object lock = new Object();
    private final ByteArrayOutputStream incoming = new ByteArrayOutputStream();

    private volatile Listener listener;
    private boolean selected;
    private boolean stopped;
    private boolean pending;
    private int le;
    private NfcResponseChunker outgoing;
    private ResponseSink deferredSink;
    private byte[] immediate;

    public NfcHolderTransport(@NonNull NfcOptions declared) {
        this.declared = declared;
    }

    @NonNull
    public NfcOptions declared() {
        return declared;
    }

    @Override
    public void start(@NonNull Listener listener) {
        this.listener = listener;
    }

    @Override
    public void send(@NonNull byte[] message) {
        ResponseSink sink;
        byte[] first;
        String progress;
        synchronized (lock) {
            if (stopped) {
                throw new ProximityException("the NFC transport is stopped");
            }
            if (!pending || le == 0) {
                throw new ProximityException(
                        "no ENVELOPE awaiting a response; the mdoc cannot push over NFC");
            }
            outgoing = new NfcResponseChunker(EnvelopeFraming.encodeDo53(message), le);
            first = encode(outgoing.next());
            progress = outgoing.delivered() + "/" + outgoing.chunks();
            pending = false;
            if (deferredSink == null) {
                immediate = first;
                return;
            }
            sink = deferredSink;
            deferredSink = null;
        }
        ProximityLogger.getInstance().d("sendResponseApdu chunk " + progress);
        sink.sendResponseApdu(first);
    }

    @Override
    public void stop() {
        ResponseSink sink = null;
        synchronized (lock) {
            if (stopped) {
                return;
            }
            stopped = true;
            outgoing = null;
            if (pending) {
                pending = false;
                if (deferredSink != null) {
                    sink = deferredSink;
                    deferredSink = null;
                } else {
                    immediate = Apdu.response(null, Type4Tag.SW_SUCCESS);
                }
            }
        }
        if (sink != null) {
            sink.sendResponseApdu(Apdu.response(null, Type4Tag.SW_SUCCESS));
        }
    }

    public boolean isLinkActive() {
        synchronized (lock) {
            return selected && !stopped;
        }
    }

    public void onDeactivated() {
        boolean live;
        synchronized (lock) {
            live = selected && !stopped;
            selected = false;
            pending = false;
            le = 0;
            outgoing = null;
            deferredSink = null;
            immediate = null;
            incoming.reset();
        }
        Listener host = listener;
        if (live && host != null) {
            host.onDisconnected();
        }
    }

    @Nullable
    public byte[] processCommandApdu(@NonNull byte[] commandApdu, @NonNull ResponseSink sink) {
        ProximityLogger logger = ProximityLogger.getInstance();
        logger.v("hce < " + Apdu.hex(commandApdu));
        byte[] response = dispatch(commandApdu, sink);
        logger.v("hce > " + (response == null ? "(deferred)" : Apdu.hex(response)));
        return response;
    }

    @Nullable
    private byte[] dispatch(@NonNull byte[] commandApdu, @NonNull ResponseSink sink) {
        Apdu.Command command;
        try {
            command = Apdu.parseCommand(commandApdu);
        } catch (ProximityException e) {
            return Apdu.response(null, NfcDataTransfer.SW_WRONG_DATA);
        }
        synchronized (lock) {
            if (stopped) {
                return Apdu.response(null, Type4Tag.SW_FILE_NOT_FOUND);
            }
        }
        if (command.ins == Type4Tag.INS_SELECT) {
            return select(command);
        }
        if (command.cla != NfcDataTransfer.CLA_CHAIN_LAST
                && command.cla != NfcDataTransfer.CLA_CHAIN_MORE) {
            return Apdu.response(null, NfcDataTransfer.SW_CLASS_NOT_SUPPORTED);
        }
        switch (command.ins) {
            case NfcDataTransfer.INS_ENVELOPE:
                return envelope(command, sink);
            case NfcDataTransfer.INS_GET_RESPONSE:
                return getResponse();
            default:
                return Apdu.response(null, Type4Tag.SW_INSTRUCTION_NOT_SUPPORTED);
        }
    }

    @NonNull
    private byte[] select(@NonNull Apdu.Command command) {
        if (command.p1 != Type4Tag.P1_SELECT_APPLICATION
                || !Arrays.equals(command.data, NfcDataTransfer.AID_MDOC_DATA)
                || (command.p2 != Type4Tag.P2_SELECT_APPLICATION
                        && command.p2 != NfcDataTransfer.P2_SELECT_DIS)) {
            return Apdu.response(null, Type4Tag.SW_FILE_NOT_FOUND);
        }
        synchronized (lock) {
            selected = true;
            pending = false;
            le = 0;
            outgoing = null;
            deferredSink = null;
            immediate = null;
            incoming.reset();
        }
        ProximityLogger.getInstance().d("hce: select A0000002480400 → 9000");
        Listener host = listener;
        if (host != null) {
            host.onConnected();
            ProximityLogger.getInstance().d("nfc leg claimed");
        }
        return Apdu.response(null, Type4Tag.SW_SUCCESS);
    }

    @Nullable
    private byte[] envelope(@NonNull Apdu.Command command, @NonNull ResponseSink sink) {
        byte[] message;
        synchronized (lock) {
            if (!selected) {
                return Apdu.response(null, NfcDataTransfer.SW_CONDITIONS_NOT_SATISFIED);
            }
            if (command.data.length == 0) {
                return Apdu.response(null, NfcDataTransfer.SW_WRONG_DATA);
            }
            incoming.write(command.data, 0, command.data.length);
            if (command.cla == NfcDataTransfer.CLA_CHAIN_MORE) {
                return Apdu.response(null, Type4Tag.SW_SUCCESS);
            }
            le = command.le == 0 ? NfcDataTransfer.SHORT_MAX_LE : command.le;
            byte[] frame = incoming.toByteArray();
            incoming.reset();
            try {
                message = EnvelopeFraming.decodeDo53(frame);
            } catch (ProximityException e) {
                ProximityLogger.getInstance().d("hce: malformed DO'53': " + e.getMessage());
                return Apdu.response(null, NfcDataTransfer.SW_WRONG_DATA);
            }
            if (message == null) {
                return Apdu.response(null, NfcDataTransfer.SW_WRONG_DATA);
            }
            outgoing = null;
            deferredSink = null;
            immediate = null;
            pending = !TerminationFrames.isTermination(message);
        }
        Listener host = listener;
        if (host == null) {
            synchronized (lock) {
                pending = false;
            }
            return Apdu.response(null, NfcDataTransfer.SW_CONDITIONS_NOT_SATISFIED);
        }
        host.onMessage(message);
        synchronized (lock) {
            if (immediate != null) {
                byte[] response = immediate;
                immediate = null;
                if (outgoing != null) {
                    ProximityLogger.getInstance().d("sendResponseApdu chunk "
                            + outgoing.delivered() + "/" + outgoing.chunks());
                }
                return response;
            }
            if (!pending) {
                return Apdu.response(null, Type4Tag.SW_SUCCESS);
            }
            deferredSink = sink;
        }
        ProximityLogger.getInstance().d("envelope deferred");
        return null;
    }

    @NonNull
    private byte[] getResponse() {
        synchronized (lock) {
            if (!selected || outgoing == null || !outgoing.hasMore()) {
                return Apdu.response(null, NfcDataTransfer.SW_CONDITIONS_NOT_SATISFIED);
            }
            byte[] response = encode(outgoing.next());
            ProximityLogger.getInstance().d("sendResponseApdu chunk "
                    + outgoing.delivered() + "/" + outgoing.chunks());
            return response;
        }
    }

    @NonNull
    private static byte[] encode(@NonNull Apdu.Response response) {
        return Apdu.response(response.data, response.status);
    }
}
