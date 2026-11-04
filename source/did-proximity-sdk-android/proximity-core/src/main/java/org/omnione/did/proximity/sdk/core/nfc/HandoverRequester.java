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
import org.omnione.did.proximity.sdk.core.engagement.ModePreference;
import org.omnione.did.proximity.sdk.core.engagement.NfcOptions;
import org.omnione.did.proximity.sdk.core.engagement.TransportMode;
import org.omnione.did.proximity.sdk.core.engagement.TransportPreference;
import org.omnione.did.proximity.sdk.core.engagement.UnsupportedTransportException;
import org.omnione.did.proximity.sdk.core.logger.ProximityLogger;

import java.io.ByteArrayOutputStream;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.UUID;

public final class HandoverRequester {

    private static final int MAX_CHUNK = 255;
    private static final int DEFAULT_MAX_NDEF_SIZE = 0xFFFF;

    static final NfcOptions READER_NFC_LIMITS =
            NfcOptions.of(NfcOptions.MAX_COMMAND_LENGTH, NfcOptions.MAX_RESPONSE_LENGTH);

    private final UUID readerServiceUuid;
    private final TransportPreference preference;
    private final byte[] collisionResolution;

    private HandoverRequester(@NonNull UUID readerServiceUuid,
                              @NonNull TransportPreference preference,
                              @NonNull byte[] collisionResolution) {
        this.readerServiceUuid = readerServiceUuid;
        this.preference = preference;
        this.collisionResolution = collisionResolution;
    }

    @NonNull
    public static HandoverRequester create(@NonNull UUID readerServiceUuid,
                                           @NonNull ModePreference preference) {
        return create(readerServiceUuid, TransportPreference.from(preference));
    }

    @NonNull
    public static HandoverRequester create(@NonNull UUID readerServiceUuid,
                                           @NonNull TransportPreference preference) {
        byte[] collisionResolution = new byte[2];
        new SecureRandom().nextBytes(collisionResolution);
        return new HandoverRequester(readerServiceUuid, preference, collisionResolution);
    }

    @NonNull
    static HandoverRequester create(@NonNull UUID readerServiceUuid,
                                    @NonNull ModePreference preference,
                                    @NonNull byte[] collisionResolution) {
        return create(readerServiceUuid, TransportPreference.from(preference),
                collisionResolution);
    }

    @NonNull
    static HandoverRequester create(@NonNull UUID readerServiceUuid,
                                    @NonNull TransportPreference preference,
                                    @NonNull byte[] collisionResolution) {
        return new HandoverRequester(readerServiceUuid, preference, collisionResolution.clone());
    }

    @NonNull
    public NfcHandoverResult engage(@NonNull ApduChannel channel) {
        Session session = new Session(channel);
        session.selectNdefApplication();
        CapabilityContainer container = session.readCapabilityContainer();
        session.selectFile(container.ndefFileId);

        byte[] initial = session.readNdefMessage(container);
        HandoverMessages.ServiceParameter service = HandoverMessages.findHandoverService(initial);
        boolean handoverSelectPresent = HandoverMessages.isHandoverSelect(initial);

        if (handoverSelectPresent) {
            if (service != null) {
                ProximityLogger.getInstance().i("nfc: initial NDEF message offers both a Handover"
                        + " Select and the " + HandoverMessages.SERVICE_NAME_HANDOVER
                        + " TNEP service; taking static handover by policy");
            }
            return staticHandover(initial, channel);
        }
        if (service == null) {
            throw new UnsupportedTransportException("the initial NDEF message is neither a Handover"
                    + " Select nor an offer of the " + HandoverMessages.SERVICE_NAME_HANDOVER
                    + " service", null);
        }
        return negotiatedHandover(session, container, service, channel);
    }

    @NonNull
    private NfcHandoverResult staticHandover(@NonNull byte[] handoverSelect,
                                             @NonNull ApduChannel channel) {
        HandoverMessages.Selected selected = HandoverMessages.parseHandoverSelect(handoverSelect);
        return choose(selected, handoverSelect, null, channel);
    }

    @NonNull
    private NfcHandoverResult choose(@NonNull HandoverMessages.Selected selected,
                                     @NonNull byte[] handoverSelect,
                                     @Nullable byte[] handoverRequest,
                                     @NonNull ApduChannel channel) {
        for (TransportMode mode : preference.order()) {
            if (mode == TransportMode.NFC) {
                if (selected.nfcCarrier != null && selected.nfcCarrier.options() != null) {
                    return NfcHandoverResult.nfc(selected.deviceEngagement,
                            selected.nfcCarrier.options(), handoverSelect, handoverRequest,
                            channel);
                }
                continue;
            }
            BleMode ble = mode.ble();
            if (ble == null || selected.carrier == null || !selected.carrier.supports(ble)) {
                continue;
            }
            return NfcHandoverResult.of(selected.deviceEngagement, ble,
                    serviceUuidFor(selected.carrier), handoverSelect, handoverRequest,
                    psmFor(ble, selected.carrier));
        }
        throw new UnsupportedTransportException(
                "the Handover Select offers none of the preferred transports", null);
    }

    private boolean prefersNfcFirst() {
        for (TransportMode mode : preference.order()) {
            if (mode == TransportMode.NFC) {
                return true;
            }
            if (mode.ble() != null) {
                return false;
            }
        }
        return false;
    }

    @Nullable
    private ModePreference bleModePreference() {
        for (TransportMode mode : preference.order()) {
            if (mode == TransportMode.BLE_CENTRAL_CLIENT) {
                return ModePreference.CENTRAL_CLIENT_FIRST;
            }
            if (mode == TransportMode.BLE_PERIPHERAL_SERVER) {
                return ModePreference.PERIPHERAL_SERVER_FIRST;
            }
        }
        return null;
    }

    @NonNull
    private NfcHandoverResult negotiatedHandover(@NonNull Session session,
                                                 @NonNull CapabilityContainer container,
                                                 @NonNull HandoverMessages.ServiceParameter service,
                                                 @NonNull ApduChannel channel) {
        if (!container.writable) {
            throw new UnsupportedTransportException("the mdoc offers negotiated handover but its"
                    + " NDEF file is read-only", null);
        }
        byte[] selectResponse = session.transact(container, service,
                HandoverMessages.serviceSelect(HandoverMessages.SERVICE_NAME_HANDOVER));
        int status = HandoverMessages.parseTnepStatus(selectResponse);
        if (status != HandoverMessages.TNEP_STATUS_SUCCESS) {
            throw new ProximityException("TNEP service selection failed with status " + status);
        }

        ModePreference blePreference = bleModePreference();
        BleCarrier offered = blePreference == null ? null : BleCarrier.of(
                EnumSet.of(BleMode.CENTRAL_CLIENT, BleMode.PERIPHERAL_SERVER), readerServiceUuid);
        NfcCarrier nfcOffered = preference.order().contains(TransportMode.NFC)
                ? NfcCarrier.readerCarrier(READER_NFC_LIMITS) : null;
        if (offered == null && nfcOffered == null) {
            throw new UnsupportedTransportException(
                    "the transport preference offers nothing negotiated handover can carry", null);
        }
        byte[] handoverRequest = HandoverMessages.handoverRequest(offered,
                blePreference == null ? ModePreference.CENTRAL_CLIENT_FIRST : blePreference,
                collisionResolution, null, nfcOffered, prefersNfcFirst());
        byte[] handoverSelect = session.transact(container, service, handoverRequest);

        HandoverMessages.Selected selected = HandoverMessages.parseHandoverSelect(handoverSelect);
        return choose(selected, handoverSelect, handoverRequest, channel);
    }

    @Nullable
    private static Integer psmFor(@NonNull BleMode mode, @NonNull BleCarrier carrier) {
        return mode == BleMode.PERIPHERAL_SERVER ? carrier.psm() : null;
    }

    @NonNull
    private UUID serviceUuidFor(@NonNull BleCarrier carrier) {
        UUID advertised = carrier.serviceUuid();
        return advertised != null ? advertised : readerServiceUuid;
    }

    private static final class CapabilityContainer {
        final int ndefFileId;
        final int maxNdefSize;
        final int maxReadLength;
        final int maxWriteLength;
        final boolean writable;

        CapabilityContainer(int ndefFileId, int maxNdefSize, int maxReadLength,
                            int maxWriteLength, boolean writable) {
            this.ndefFileId = ndefFileId;
            this.maxNdefSize = maxNdefSize;
            this.maxReadLength = maxReadLength;
            this.maxWriteLength = maxWriteLength;
            this.writable = writable;
        }
    }

    private static final class Session {
        private final ApduChannel channel;

        Session(@NonNull ApduChannel channel) {
            this.channel = channel;
        }

        @NonNull
        byte[] exchange(@NonNull byte[] command, @NonNull String what) {
            ProximityLogger logger = ProximityLogger.getInstance();
            logger.v("nfc > " + Apdu.hex(command));
            byte[] raw = channel.transceive(command);
            logger.v("nfc < " + Apdu.hex(raw));

            Apdu.Response response = Apdu.parseResponse(raw);
            if (!response.isSuccess()) {
                throw new ProximityException(String.format("%s failed with status %04X",
                        what, response.status));
            }
            return response.data;
        }

        void selectNdefApplication() {
            exchange(Apdu.selectApplication(Type4Tag.NDEF_APPLICATION_ID),
                    "SELECT of the NDEF tag application");
        }

        void selectFile(int fileId) {
            exchange(Apdu.selectFile(fileId), String.format("SELECT of file %04X", fileId));
        }

        @NonNull
        CapabilityContainer readCapabilityContainer() {
            selectFile(Type4Tag.FILE_ID_CAPABILITY_CONTAINER);
            byte[] container = exchange(
                    Apdu.readBinary(0, Type4Tag.CAPABILITY_CONTAINER_LENGTH),
                    "READ BINARY of the capability container");
            if (container.length < Type4Tag.CAPABILITY_CONTAINER_LENGTH) {
                throw new ProximityException("capability container is " + container.length
                        + " bytes, expected " + Type4Tag.CAPABILITY_CONTAINER_LENGTH);
            }
            if ((container[7] & 0xFF) != 0x04 || (container[8] & 0xFF) != 0x06) {
                throw new ProximityException("no NDEF File Control TLV in the capability container");
            }
            int maxLe = ((container[3] & 0xFF) << 8) | (container[4] & 0xFF);
            int maxLc = ((container[5] & 0xFF) << 8) | (container[6] & 0xFF);
            int fileId = ((container[9] & 0xFF) << 8) | (container[10] & 0xFF);
            int maxNdefSize = ((container[11] & 0xFF) << 8) | (container[12] & 0xFF);
            boolean writable = (container[14] & 0xFF) == Type4Tag.WRITE_ACCESS_GRANTED;

            return new CapabilityContainer(fileId, maxNdefSize,
                    chunk(maxLe - 2), chunk(maxLc - 5), writable);
        }

        private int chunk(int available) {
            int usable = Math.min(available, Math.min(MAX_CHUNK, channel.maxTransceiveLength() - 8));
            if (usable < 1) {
                throw new ProximityException("the tag leaves no room for a short-form transfer");
            }
            return usable;
        }

        @NonNull
        byte[] readNdefMessage(@NonNull CapabilityContainer container) {
            int length = readNlen(container);
            if (length < 1) {
                throw new ProximityException("the NDEF file is empty");
            }
            ByteArrayOutputStream message = new ByteArrayOutputStream(length);
            int offset = Type4Tag.NLEN_LENGTH;
            int remaining = length;
            while (remaining > 0) {
                int wanted = Math.min(remaining, container.maxReadLength);
                byte[] part = exchange(Apdu.readBinary(offset, wanted),
                        "READ BINARY of the NDEF file");
                if (part.length == 0) {
                    throw new ProximityException("the tag returned no data for a READ BINARY");
                }
                message.write(part, 0, part.length);
                offset += part.length;
                remaining -= part.length;
            }
            return message.toByteArray();
        }

        private int readNlen(@NonNull CapabilityContainer container) {
            byte[] nlen = exchange(Apdu.readBinary(0, Type4Tag.NLEN_LENGTH),
                    "READ BINARY of NLEN");
            if (nlen.length < Type4Tag.NLEN_LENGTH) {
                throw new ProximityException("short NLEN read");
            }
            return ((nlen[0] & 0xFF) << 8) | (nlen[1] & 0xFF);
        }

        @NonNull
        byte[] transact(@NonNull CapabilityContainer container,
                        @NonNull HandoverMessages.ServiceParameter service,
                        @NonNull byte[] ndefMessage) {
            int limit = service.maxNdefSize > 0 ? service.maxNdefSize : DEFAULT_MAX_NDEF_SIZE;
            limit = Math.min(limit, container.maxNdefSize > 0
                    ? container.maxNdefSize : DEFAULT_MAX_NDEF_SIZE);
            if (ndefMessage.length > limit) {
                throw new ProximityException("NDEF message of " + ndefMessage.length
                        + " bytes exceeds the tag limit of " + limit);
            }
            writeNdefMessage(container, ndefMessage);
            return awaitResponse(container, service);
        }

        private void writeNdefMessage(@NonNull CapabilityContainer container,
                                      @NonNull byte[] ndefMessage) {
            exchange(Apdu.updateBinary(0, new byte[]{0x00, 0x00}),
                    "UPDATE BINARY resetting NLEN");

            int offset = Type4Tag.NLEN_LENGTH;
            int written = 0;
            while (written < ndefMessage.length) {
                int size = Math.min(container.maxWriteLength, ndefMessage.length - written);
                exchange(Apdu.updateBinary(offset,
                                Arrays.copyOfRange(ndefMessage, written, written + size)),
                        "UPDATE BINARY of the NDEF file");
                offset += size;
                written += size;
            }

            exchange(Apdu.updateBinary(0, new byte[]{
                            (byte) (ndefMessage.length >>> 8), (byte) ndefMessage.length}),
                    "UPDATE BINARY committing NLEN");
        }

        @NonNull
        private byte[] awaitResponse(@NonNull CapabilityContainer container,
                                     @NonNull HandoverMessages.ServiceParameter service) {
            long waitMillis = service.waitMillis();
            int attempts = Math.max(1, service.nWait) + 1;
            for (int attempt = 0; attempt < attempts; attempt++) {
                sleep(waitMillis);
                if (readNlen(container) > 0) {
                    return readNdefMessage(container);
                }
                ProximityLogger.getInstance().d("nfc: tag asked for more time (attempt "
                        + (attempt + 1) + " of " + attempts + ")");
            }
            throw new ProximityException("the tag never produced a TNEP response within "
                    + attempts + " waiting periods");
        }

        private void sleep(long millis) {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ProximityException("interrupted while waiting for a TNEP response", e);
            }
        }
    }
}
