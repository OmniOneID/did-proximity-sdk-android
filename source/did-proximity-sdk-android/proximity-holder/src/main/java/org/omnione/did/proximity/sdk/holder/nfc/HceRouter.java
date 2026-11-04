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

package org.omnione.did.proximity.sdk.holder.nfc;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.omnione.did.proximity.sdk.core.ProximityException;
import org.omnione.did.proximity.sdk.core.logger.ProximityLogger;
import org.omnione.did.proximity.sdk.core.nfc.Apdu;
import org.omnione.did.proximity.sdk.core.nfc.HandoverResponder;
import org.omnione.did.proximity.sdk.core.nfc.Type4Tag;
import org.omnione.did.proximity.sdk.core.transport.nfc.NfcDataTransfer;
import org.omnione.did.proximity.sdk.core.transport.nfc.NfcHolderTransport;

import java.util.Arrays;

public final class HceRouter {

    public interface ResponderSource {
        @Nullable
        HandoverResponder responder();
    }

    public interface TransportSource {
        @Nullable
        NfcHolderTransport transport();
    }

    private enum Route {
        HANDOVER,
        DATA
    }

    private static final byte[] SW_FILE_NOT_FOUND = {0x6A, (byte) 0x82};

    private final ResponderSource responders;
    private final TransportSource transports;
    private Route active;

    public HceRouter(@NonNull ResponderSource responders, @NonNull TransportSource transports) {
        this.responders = responders;
        this.transports = transports;
    }

    @Nullable
    public byte[] processCommandApdu(@NonNull byte[] commandApdu,
                                     @NonNull NfcHolderTransport.ResponseSink sink) {
        byte[] aid = selectedAid(commandApdu);
        if (aid != null) {
            if (Arrays.equals(aid, Type4Tag.NDEF_APPLICATION_ID)) {
                active = Route.HANDOVER;
            } else if (Arrays.equals(aid, NfcDataTransfer.AID_MDOC_DATA)) {
                active = Route.DATA;
            } else {
                ProximityLogger.getInstance().d("hce: select of an unknown aid "
                        + Apdu.hex(aid));
                return SW_FILE_NOT_FOUND.clone();
            }
        }
        if (active == Route.DATA) {
            NfcHolderTransport transport = transports.transport();
            if (transport == null) {
                ProximityLogger.getInstance().d("hce: no nfc data transport, serving nothing");
                return SW_FILE_NOT_FOUND.clone();
            }
            return transport.processCommandApdu(commandApdu, sink);
        }
        HandoverResponder responder = responders.responder();
        if (responder == null) {
            ProximityLogger.getInstance().d("hce: no live holder session, serving nothing");
            return SW_FILE_NOT_FOUND.clone();
        }
        try {
            return responder.processCommandApdu(commandApdu);
        } catch (ProximityException e) {
            ProximityLogger.getInstance().e("hce: " + e);
            return SW_FILE_NOT_FOUND.clone();
        }
    }

    public void onDeactivated() {
        active = null;
        HandoverResponder responder = responders.responder();
        if (responder != null) {
            responder.onDeactivated();
        }
        NfcHolderTransport transport = transports.transport();
        if (transport != null) {
            transport.onDeactivated();
        }
    }

    @Nullable
    private static byte[] selectedAid(@NonNull byte[] commandApdu) {
        Apdu.Command command;
        try {
            command = Apdu.parseCommand(commandApdu);
        } catch (ProximityException e) {
            return null;
        }
        if (command.cla != Type4Tag.CLA || command.ins != Type4Tag.INS_SELECT
                || command.p1 != Type4Tag.P1_SELECT_APPLICATION) {
            return null;
        }
        if (command.p2 != Type4Tag.P2_SELECT_APPLICATION) {
            ProximityLogger.getInstance().d("hce: select by aid with p2 "
                    + Integer.toHexString(command.p2));
        }
        return command.data;
    }
}
