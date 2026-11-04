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
import org.omnione.did.proximity.sdk.core.engagement.BleMode;
import org.omnione.did.proximity.sdk.core.engagement.NfcOptions;
import org.omnione.did.proximity.sdk.core.engagement.TransportMode;
import org.omnione.did.proximity.sdk.core.logger.ProximityLogger;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

public final class HandoverResponder {

    public interface Listener {
        void onHandoverSelectServed(@NonNull byte[] handoverSelect,
                                    @Nullable byte[] handoverRequest);
    }

    public static final int TNEP_WAIT_TIME_INT = 0;
    public static final int TNEP_MAX_WAIT_EXTENSIONS = 15;
    public static final int MAX_NDEF_SIZE = 0x7FFF;

    private enum Stage {
        INITIAL,
        SERVICE_SELECTED,
        DONE
    }

    private final byte[] deviceEngagement;
    private final Set<BleMode> supportedModes;
    private final UUID serviceUuid;
    private final Integer psm;
    private final NfcCarrier nfcCarrier;
    private final boolean negotiated;
    private final Listener listener;

    private byte[] ndefFile;
    private BleMode selectedMode;
    private TransportMode selectedTransport;
    private boolean served;
    private Stage stage = Stage.INITIAL;
    private int selectedFile = -1;
    private boolean applicationSelected;
    private ByteArrayOutputStream pendingWrite;

    private HandoverResponder(@NonNull byte[] deviceEngagement,
                              @NonNull Set<BleMode> supportedModes,
                              @Nullable UUID serviceUuid,
                              @Nullable Integer psm,
                              @Nullable NfcCarrier nfcCarrier,
                              boolean negotiated,
                              @NonNull Listener listener) {
        this.deviceEngagement = deviceEngagement;
        this.supportedModes = supportedModes;
        this.serviceUuid = serviceUuid;
        this.psm = psm;
        this.nfcCarrier = nfcCarrier;
        this.negotiated = negotiated;
        this.listener = listener;
        this.ndefFile = initialFile();
    }

    @NonNull
    public static HandoverResponder staticHandover(@NonNull byte[] deviceEngagement,
                                                   @NonNull BleCarrier carrier,
                                                   @NonNull Listener listener) {
        return staticHandover(deviceEngagement, carrier, null, listener);
    }

    @NonNull
    public static HandoverResponder staticHandover(@NonNull byte[] deviceEngagement,
                                                   @NonNull BleCarrier carrier,
                                                   @Nullable NfcCarrier nfcCarrier,
                                                   @NonNull Listener listener) {
        if (carrier.serviceUuid() == null) {
            throw new ProximityException("a static Handover Select must carry one service UUID");
        }
        return new HandoverResponder(deviceEngagement.clone(),
                EnumSet.copyOf(carrier.modes()), carrier.serviceUuid(), carrier.psm(),
                nfcCarrier, false, listener);
    }

    @NonNull
    public static HandoverResponder negotiatedHandover(@NonNull byte[] deviceEngagement,
                                                       @NonNull Set<BleMode> supported,
                                                       @NonNull UUID serviceUuid,
                                                       @NonNull Listener listener) {
        return negotiatedHandover(deviceEngagement, supported, serviceUuid, null, listener);
    }

    @NonNull
    public static HandoverResponder negotiatedHandover(@NonNull byte[] deviceEngagement,
                                                       @NonNull Set<BleMode> supported,
                                                       @NonNull UUID serviceUuid,
                                                       @Nullable Integer psm,
                                                       @NonNull Listener listener) {
        return negotiatedHandover(deviceEngagement, supported, serviceUuid, psm, null, listener);
    }

    @NonNull
    public static HandoverResponder negotiatedHandover(@NonNull byte[] deviceEngagement,
                                                       @NonNull Set<BleMode> supported,
                                                       @NonNull UUID serviceUuid,
                                                       @Nullable Integer psm,
                                                       @Nullable NfcOptions nfcOptions,
                                                       @NonNull Listener listener) {
        if (supported.isEmpty()) {
            throw new ProximityException("negotiated handover needs at least one BLE mode");
        }
        if (psm != null && !supported.contains(BleMode.PERIPHERAL_SERVER)) {
            throw new ProximityException(
                    "an L2CAP PSM needs the BLE peripheral server mode");
        }
        return new HandoverResponder(deviceEngagement.clone(),
                EnumSet.copyOf(supported), serviceUuid, psm, NfcCarrier.of(nfcOptions), true,
                listener);
    }

    @NonNull
    public byte[] processCommandApdu(@NonNull byte[] commandApdu) {
        ProximityLogger logger = ProximityLogger.getInstance();
        logger.v("hce < " + Apdu.hex(commandApdu));
        byte[] response = dispatch(commandApdu);
        logger.v("hce > " + Apdu.hex(response));
        return response;
    }

    public void onDeactivated() {
        stage = negotiated ? Stage.INITIAL : Stage.DONE;
        selectedFile = -1;
        applicationSelected = false;
        pendingWrite = null;
        served = false;
        selectedMode = null;
        selectedTransport = null;
        ndefFile = initialFile();
    }

    @NonNull
    public Set<BleMode> advertisedModes() {
        return supportedModes;
    }

    @Nullable
    public BleMode selectedMode() {
        return selectedMode;
    }

    @Nullable
    public TransportMode selectedTransport() {
        return selectedTransport;
    }

    @Nullable
    public NfcCarrier nfcCarrier() {
        return nfcCarrier;
    }

    @NonNull
    public UUID serviceUuid() {
        return serviceUuid;
    }

    public boolean servedHandoverSelect() {
        return served;
    }

    @NonNull
    private byte[] dispatch(@NonNull byte[] commandApdu) {
        Apdu.Command command;
        try {
            command = Apdu.parseCommand(commandApdu);
        } catch (ProximityException e) {
            return reset(Type4Tag.SW_WRONG_PARAMETERS);
        }
        if (command.cla != Type4Tag.CLA) {
            return Apdu.response(null, Type4Tag.SW_INSTRUCTION_NOT_SUPPORTED);
        }
        switch (command.ins) {
            case Type4Tag.INS_SELECT:
                return select(command);
            case Type4Tag.INS_READ_BINARY:
                return readBinary(command);
            case Type4Tag.INS_UPDATE_BINARY:
                return updateBinary(command);
            default:
                return Apdu.response(null, Type4Tag.SW_INSTRUCTION_NOT_SUPPORTED);
        }
    }

    @NonNull
    private byte[] select(@NonNull Apdu.Command command) {
        if (command.p1 == Type4Tag.P1_SELECT_APPLICATION) {
            if (!Arrays.equals(command.data, Type4Tag.NDEF_APPLICATION_ID)) {
                return reset(Type4Tag.SW_FILE_NOT_FOUND);
            }
            applicationSelected = true;
            return Apdu.response(null, Type4Tag.SW_SUCCESS);
        }
        if (command.p1 != Type4Tag.P1_SELECT_FILE || command.data.length != 2) {
            return reset(Type4Tag.SW_WRONG_PARAMETERS);
        }
        if (!applicationSelected) {
            return reset(Type4Tag.SW_FILE_NOT_FOUND);
        }
        int fileId = ((command.data[0] & 0xFF) << 8) | (command.data[1] & 0xFF);
        if (fileId != Type4Tag.FILE_ID_CAPABILITY_CONTAINER && fileId != Type4Tag.FILE_ID_NDEF) {
            return reset(Type4Tag.SW_FILE_NOT_FOUND);
        }
        selectedFile = fileId;
        return Apdu.response(null, Type4Tag.SW_SUCCESS);
    }

    @NonNull
    private byte[] readBinary(@NonNull Apdu.Command command) {
        byte[] source = source();
        if (source == null) {
            return reset(Type4Tag.SW_FILE_NOT_FOUND);
        }
        int offset = command.offset();
        if (offset > source.length) {
            return reset(Type4Tag.SW_WRONG_PARAMETERS);
        }
        int length = Math.min(command.le, source.length - offset);
        byte[] response = Apdu.response(Arrays.copyOfRange(source, offset, offset + length),
                Type4Tag.SW_SUCCESS);
        if (!negotiated && !served && selectedFile == Type4Tag.FILE_ID_NDEF
                && offset + length == ndefFile.length && ndefFile.length > Type4Tag.NLEN_LENGTH) {
            served = true;
            stage = Stage.DONE;
            listener.onHandoverSelectServed(staticHandoverSelect(), null);
        }
        return response;
    }

    @NonNull
    private byte[] updateBinary(@NonNull Apdu.Command command) {
        if (!negotiated || selectedFile != Type4Tag.FILE_ID_NDEF) {
            return reset(Type4Tag.SW_WRONG_PARAMETERS);
        }
        int offset = command.offset();
        if (offset == 0) {
            if (command.data.length != Type4Tag.NLEN_LENGTH) {
                return reset(Type4Tag.SW_WRONG_PARAMETERS);
            }
            int declared = ((command.data[0] & 0xFF) << 8) | (command.data[1] & 0xFF);
            if (declared == 0) {
                pendingWrite = new ByteArrayOutputStream();
                return Apdu.response(null, Type4Tag.SW_SUCCESS);
            }
            return commit(declared);
        }
        if (pendingWrite == null) {
            return reset(Type4Tag.SW_WRONG_PARAMETERS);
        }
        if (offset != Type4Tag.NLEN_LENGTH + pendingWrite.size()) {
            return reset(Type4Tag.SW_WRONG_PARAMETERS);
        }
        if (pendingWrite.size() + command.data.length > MAX_NDEF_SIZE) {
            return reset(Type4Tag.SW_WRONG_PARAMETERS);
        }
        pendingWrite.write(command.data, 0, command.data.length);
        return Apdu.response(null, Type4Tag.SW_SUCCESS);
    }

    @NonNull
    private byte[] commit(int declaredLength) {
        if (pendingWrite == null) {
            return reset(Type4Tag.SW_WRONG_PARAMETERS);
        }
        byte[] written = pendingWrite.toByteArray();
        pendingWrite = null;
        if (written.length != declaredLength) {
            return reset(Type4Tag.SW_WRONG_PARAMETERS);
        }
        try {
            return handle(written);
        } catch (ProximityException e) {
            ProximityLogger.getInstance().e("hce: rejecting a written NDEF message: "
                    + e.getMessage());
            return reset(Type4Tag.SW_WRONG_PARAMETERS);
        }
    }

    @NonNull
    private byte[] handle(@NonNull byte[] message) {
        if (stage == Stage.INITIAL) {
            String service = HandoverMessages.parseServiceSelect(message);
            if (!HandoverMessages.SERVICE_NAME_HANDOVER.equals(service)) {
                return reset(Type4Tag.SW_WRONG_PARAMETERS);
            }
            stage = Stage.SERVICE_SELECTED;
            ndefFile = file(HandoverMessages.tnepStatus(HandoverMessages.TNEP_STATUS_SUCCESS));
            return Apdu.response(null, Type4Tag.SW_SUCCESS);
        }
        if (stage != Stage.SERVICE_SELECTED) {
            return reset(Type4Tag.SW_WRONG_PARAMETERS);
        }

        HandoverMessages.Requested requested = HandoverMessages.parseHandoverRequest(message);
        Choice choice = choose(requested);
        byte[] handoverSelect;
        if (choice.transport == TransportMode.NFC) {
            handoverSelect = HandoverMessages.handoverSelect(null, nfcCarrier, deviceEngagement);
        } else {
            handoverSelect = HandoverMessages.handoverSelect(
                    BleCarrier.of(EnumSet.of(choice.ble), serviceUuid)
                            .withPsm(choice.ble == BleMode.PERIPHERAL_SERVER ? psm : null),
                    null, deviceEngagement);
        }

        stage = Stage.DONE;
        selectedMode = choice.ble;
        selectedTransport = choice.transport;
        served = true;
        ndefFile = file(handoverSelect);
        listener.onHandoverSelectServed(handoverSelect, message);
        return Apdu.response(null, Type4Tag.SW_SUCCESS);
    }

    private static final class Choice {
        @NonNull final TransportMode transport;
        @Nullable final BleMode ble;

        Choice(@NonNull TransportMode transport, @Nullable BleMode ble) {
            this.transport = transport;
            this.ble = ble;
        }
    }

    @NonNull
    private Choice choose(@NonNull HandoverMessages.Requested requested) {
        Set<BleMode> offered = EnumSet.noneOf(BleMode.class);
        for (BleCarrier carrier : requested.carriers) {
            offered.addAll(carrier.modes());
        }
        offered.retainAll(supportedModes);
        boolean nfcOffered = requested.nfcCarrier != null && nfcCarrier != null;
        if (nfcOffered && (requested.nfcFirst || offered.isEmpty())) {
            return new Choice(TransportMode.NFC, null);
        }
        if (offered.contains(BleMode.CENTRAL_CLIENT)) {
            return new Choice(TransportMode.BLE_CENTRAL_CLIENT, BleMode.CENTRAL_CLIENT);
        }
        if (offered.contains(BleMode.PERIPHERAL_SERVER)) {
            return new Choice(TransportMode.BLE_PERIPHERAL_SERVER, BleMode.PERIPHERAL_SERVER);
        }
        throw new ProximityException("the reader offers no transport this mdoc supports");
    }

    @Nullable
    private byte[] source() {
        if (selectedFile == Type4Tag.FILE_ID_CAPABILITY_CONTAINER) {
            return capabilityContainer();
        }
        if (selectedFile == Type4Tag.FILE_ID_NDEF) {
            return ndefFile;
        }
        return null;
    }

    @NonNull
    private byte[] capabilityContainer() {
        return new byte[]{
                0x00, 0x0F, 0x20, 0x7F, (byte) 0xFF, 0x7F, (byte) 0xFF,
                0x04, 0x06, (byte) 0xE1, 0x04, 0x7F, (byte) 0xFF, 0x00,
                (byte) (negotiated ? Type4Tag.WRITE_ACCESS_GRANTED : Type4Tag.WRITE_ACCESS_DENIED)
        };
    }

    @NonNull
    private byte[] initialFile() {
        if (!negotiated) {
            byte[] handoverSelect = HandoverMessages.handoverSelect(
                    BleCarrier.of(supportedModes, serviceUuid).withPsm(psm), nfcCarrier,
                    deviceEngagement);
            return file(handoverSelect);
        }
        return file(HandoverMessages.initialNegotiatedMessage(
                TNEP_WAIT_TIME_INT, TNEP_MAX_WAIT_EXTENSIONS, MAX_NDEF_SIZE));
    }

    @NonNull
    public byte[] staticHandoverSelect() {
        if (negotiated) {
            throw new ProximityException("this responder serves negotiated handover");
        }
        return Arrays.copyOfRange(ndefFile, Type4Tag.NLEN_LENGTH, ndefFile.length);
    }

    @NonNull
    private byte[] reset(int status) {
        pendingWrite = null;
        if (negotiated && stage != Stage.DONE) {
            stage = Stage.INITIAL;
            ndefFile = initialFile();
        }
        return Apdu.response(null, status);
    }

    @NonNull
    private static byte[] file(@NonNull byte[] ndefMessage) {
        byte[] out = new byte[ndefMessage.length + Type4Tag.NLEN_LENGTH];
        out[0] = (byte) (ndefMessage.length >>> 8);
        out[1] = (byte) ndefMessage.length;
        System.arraycopy(ndefMessage, 0, out, Type4Tag.NLEN_LENGTH, ndefMessage.length);
        return out;
    }
}
