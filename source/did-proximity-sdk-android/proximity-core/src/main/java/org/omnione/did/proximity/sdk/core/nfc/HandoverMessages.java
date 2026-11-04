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

import com.upokecenter.cbor.CBORObject;

import org.omnione.did.proximity.sdk.core.ProximityException;
import org.omnione.did.proximity.sdk.core.engagement.ModePreference;
import org.omnione.did.proximity.sdk.core.engagement.UnsupportedTransportException;
import org.omnione.did.proximity.sdk.core.logger.ProximityLogger;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class HandoverMessages {

    public static final String RTD_HANDOVER_SELECT = "Hs";
    public static final String RTD_HANDOVER_REQUEST = "Hr";
    public static final String RTD_ALTERNATIVE_CARRIER = "ac";
    public static final String RTD_COLLISION_RESOLUTION = "cr";
    public static final String RTD_SERVICE_PARAMETER = "Tp";
    public static final String RTD_SERVICE_SELECT = "Ts";
    public static final String RTD_TNEP_STATUS = "Te";

    public static final String SERVICE_NAME_HANDOVER = "urn:nfc:sn:handover";
    public static final String TYPE_DEVICE_ENGAGEMENT = "iso.org:18013:deviceengagement";
    public static final String ID_MDOC = "mdoc";
    public static final String TYPE_READER_ENGAGEMENT = "iso.org:18013:readerengagement";
    public static final String ID_MDOC_READER = "mdocreader";

    public static final int CH_VERSION = 0x15;
    public static final int CPS_ACTIVE = 0x01;
    public static final int TNEP_VERSION = 0x10;
    public static final int TNEP_SINGLE_RESPONSE = 0x00;
    public static final int TNEP_STATUS_SUCCESS = 0x00;

    static final String CARRIER_REF_BLE = "0";
    static final String CARRIER_REF_NFC = "nfc";

    private HandoverMessages() {
    }

    public static final class Selected {
        @Nullable public final BleCarrier carrier;
        @Nullable public final NfcCarrier nfcCarrier;
        @NonNull public final byte[] deviceEngagement;

        Selected(@Nullable BleCarrier carrier, @Nullable NfcCarrier nfcCarrier,
                 @NonNull byte[] deviceEngagement) {
            this.carrier = carrier;
            this.nfcCarrier = nfcCarrier;
            this.deviceEngagement = deviceEngagement;
        }
    }

    public static final class Requested {
        @NonNull public final List<BleCarrier> carriers;
        @Nullable public final NfcCarrier nfcCarrier;
        public final boolean nfcFirst;

        Requested(@NonNull List<BleCarrier> carriers, @Nullable NfcCarrier nfcCarrier,
                  boolean nfcFirst) {
            this.carriers = Collections.unmodifiableList(carriers);
            this.nfcCarrier = nfcCarrier;
            this.nfcFirst = nfcFirst;
        }
    }

    private static final class Carriers {
        final List<BleCarrier> ble = new ArrayList<>();
        NfcCarrier nfc;
        boolean nfcFirst;
    }

    public static final class ServiceParameter {
        public final int tnepVersion;
        @NonNull public final String serviceName;
        public final int communicationMode;
        public final int wtInt;
        public final int nWait;
        public final int maxNdefSize;

        ServiceParameter(int tnepVersion, @NonNull String serviceName, int communicationMode,
                         int wtInt, int nWait, int maxNdefSize) {
            this.tnepVersion = tnepVersion;
            this.serviceName = serviceName;
            this.communicationMode = communicationMode;
            this.wtInt = wtInt;
            this.nWait = nWait;
            this.maxNdefSize = maxNdefSize;
        }

        public long waitMillis() {
            return Math.max(1L, (long) Math.ceil(Math.pow(2.0, wtInt / 4.0 - 1.0)));
        }
    }

    @NonNull
    public static byte[] handoverSelect(@NonNull BleCarrier carrier,
                                        @NonNull byte[] deviceEngagement) {
        return handoverSelect(carrier, null, deviceEngagement);
    }

    @NonNull
    public static byte[] handoverSelect(@Nullable BleCarrier carrier,
                                        @Nullable NfcCarrier nfcCarrier,
                                        @NonNull byte[] deviceEngagement) {
        if (carrier == null && nfcCarrier == null) {
            throw new ProximityException("a Handover Select needs at least one carrier");
        }
        List<NdefRecord> acs = new ArrayList<>();
        List<NdefRecord> configs = new ArrayList<>();
        if (carrier != null) {
            acs.add(alternativeCarrier(CARRIER_REF_BLE, Collections.singletonList(ID_MDOC)));
            configs.add(NdefRecord.of(NdefRecord.TNF_MIME_MEDIA, BleCarrier.RECORD_TYPE,
                    CARRIER_REF_BLE, carrier.toHandoverSelect()));
        }
        if (nfcCarrier != null) {
            acs.add(alternativeCarrier(CARRIER_REF_NFC, Collections.singletonList(ID_MDOC)));
            configs.add(NdefRecord.of(NdefRecord.TNF_EXTERNAL_TYPE, NfcCarrier.RECORD_TYPE,
                    CARRIER_REF_NFC, nfcCarrier.encode()));
        }
        NdefRecord hs = NdefRecord.of(NdefRecord.TNF_WELL_KNOWN, RTD_HANDOVER_SELECT, null,
                withChVersion(NdefMessage.of(acs).encode()));
        List<NdefRecord> records = new ArrayList<>();
        records.add(hs);
        records.addAll(configs);
        records.add(NdefRecord.of(NdefRecord.TNF_EXTERNAL_TYPE, TYPE_DEVICE_ENGAGEMENT,
                ID_MDOC, deviceEngagement));
        return NdefMessage.of(records).encode();
    }

    @NonNull
    public static byte[] handoverRequest(@NonNull BleCarrier carrier,
                                         @NonNull ModePreference preference,
                                         @NonNull byte[] collisionResolution,
                                         @Nullable byte[] readerEngagement) {
        return handoverRequest(carrier, preference, collisionResolution, readerEngagement,
                null, false);
    }

    @NonNull
    public static byte[] handoverRequest(@Nullable BleCarrier carrier,
                                         @NonNull ModePreference preference,
                                         @NonNull byte[] collisionResolution,
                                         @Nullable byte[] readerEngagement,
                                         @Nullable NfcCarrier nfcCarrier,
                                         boolean nfcFirst) {
        if (collisionResolution.length != 2) {
            throw new ProximityException(
                    "collision resolution must be 2 bytes, got " + collisionResolution.length);
        }
        if (carrier == null && nfcCarrier == null) {
            throw new ProximityException("a Handover Request needs at least one carrier");
        }
        List<String> auxReferences = readerEngagement == null
                ? Collections.<String>emptyList()
                : Collections.singletonList(ID_MDOC_READER);

        NdefRecord cr = NdefRecord.of(NdefRecord.TNF_WELL_KNOWN, RTD_COLLISION_RESOLUTION, null,
                collisionResolution.clone());
        List<NdefRecord> embedded = new ArrayList<>();
        embedded.add(cr);
        List<NdefRecord> configs = new ArrayList<>();
        NdefRecord bleAc = carrier == null ? null
                : alternativeCarrier(CARRIER_REF_BLE, auxReferences);
        NdefRecord bleConfig = carrier == null ? null
                : NdefRecord.of(NdefRecord.TNF_MIME_MEDIA, BleCarrier.RECORD_TYPE,
                        CARRIER_REF_BLE, carrier.toHandoverRequest(preference));
        NdefRecord nfcAc = nfcCarrier == null ? null
                : alternativeCarrier(CARRIER_REF_NFC, Collections.<String>emptyList());
        NdefRecord nfcConfig = nfcCarrier == null ? null
                : NdefRecord.of(NdefRecord.TNF_EXTERNAL_TYPE, NfcCarrier.RECORD_TYPE,
                        CARRIER_REF_NFC, nfcCarrier.encode());
        if (nfcFirst) {
            addIfPresent(embedded, nfcAc);
            addIfPresent(configs, nfcConfig);
            addIfPresent(embedded, bleAc);
            addIfPresent(configs, bleConfig);
        } else {
            addIfPresent(embedded, bleAc);
            addIfPresent(configs, bleConfig);
            addIfPresent(embedded, nfcAc);
            addIfPresent(configs, nfcConfig);
        }
        NdefRecord hr = NdefRecord.of(NdefRecord.TNF_WELL_KNOWN, RTD_HANDOVER_REQUEST, null,
                withChVersion(NdefMessage.of(embedded).encode()));

        List<NdefRecord> records = new ArrayList<>();
        records.add(hr);
        records.addAll(configs);
        if (readerEngagement != null) {
            records.add(NdefRecord.of(NdefRecord.TNF_EXTERNAL_TYPE, TYPE_READER_ENGAGEMENT,
                    ID_MDOC_READER, readerEngagement));
        }
        return NdefMessage.of(records).encode();
    }

    private static void addIfPresent(@NonNull List<NdefRecord> records,
                                     @Nullable NdefRecord record) {
        if (record != null) {
            records.add(record);
        }
    }

    @NonNull
    public static byte[] readerEngagement() {
        CBORObject engagement = CBORObject.NewMap();
        engagement.Add(0, "1.0");
        return engagement.EncodeToBytes();
    }

    @NonNull
    public static byte[] initialNegotiatedMessage(int wtInt, int nWait, int maxNdefSize) {
        byte[] serviceName = SERVICE_NAME_HANDOVER.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        payload.write(TNEP_VERSION);
        payload.write(serviceName.length);
        payload.write(serviceName, 0, serviceName.length);
        payload.write(TNEP_SINGLE_RESPONSE);
        payload.write(wtInt);
        payload.write(nWait);
        payload.write((maxNdefSize >>> 8) & 0xFF);
        payload.write(maxNdefSize & 0xFF);
        return NdefMessage.of(NdefRecord.of(NdefRecord.TNF_WELL_KNOWN, RTD_SERVICE_PARAMETER, null,
                payload.toByteArray())).encode();
    }

    @NonNull
    public static byte[] serviceSelect(@NonNull String serviceName) {
        byte[] name = serviceName.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        payload.write(name.length);
        payload.write(name, 0, name.length);
        return NdefMessage.of(NdefRecord.of(NdefRecord.TNF_WELL_KNOWN, RTD_SERVICE_SELECT, null,
                payload.toByteArray())).encode();
    }

    @NonNull
    public static byte[] tnepStatus(int statusCode) {
        return NdefMessage.of(NdefRecord.of(NdefRecord.TNF_WELL_KNOWN, RTD_TNEP_STATUS, null,
                new byte[]{(byte) statusCode})).encode();
    }

    public static int parseTnepStatus(@NonNull byte[] ndefMessage) {
        NdefRecord status = NdefMessage.parse(ndefMessage)
                .find(NdefRecord.TNF_WELL_KNOWN, RTD_TNEP_STATUS);
        if (status == null) {
            throw new ProximityException("no TNEP status record in the response");
        }
        byte[] payload = status.payload();
        if (payload.length < 1) {
            throw new ProximityException("empty TNEP status record");
        }
        return payload[0] & 0xFF;
    }

    @Nullable
    public static String parseServiceSelect(@NonNull byte[] ndefMessage) {
        NdefRecord select = NdefMessage.parse(ndefMessage)
                .find(NdefRecord.TNF_WELL_KNOWN, RTD_SERVICE_SELECT);
        if (select == null) {
            return null;
        }
        byte[] payload = select.payload();
        if (payload.length < 1 || (payload[0] & 0xFF) != payload.length - 1) {
            throw new ProximityException("malformed TNEP service select record");
        }
        return new String(payload, 1, payload.length - 1, StandardCharsets.UTF_8);
    }

    public static boolean isHandoverSelect(@NonNull byte[] ndefMessage) {
        return NdefMessage.parse(ndefMessage)
                .find(NdefRecord.TNF_WELL_KNOWN, RTD_HANDOVER_SELECT) != null;
    }

    public static boolean isHandoverRequest(@NonNull byte[] ndefMessage) {
        return NdefMessage.parse(ndefMessage)
                .find(NdefRecord.TNF_WELL_KNOWN, RTD_HANDOVER_REQUEST) != null;
    }

    @Nullable
    public static ServiceParameter findHandoverService(@NonNull byte[] ndefMessage) {
        for (NdefRecord record : NdefMessage.parse(ndefMessage).records()) {
            if (!record.is(NdefRecord.TNF_WELL_KNOWN, RTD_SERVICE_PARAMETER)) {
                continue;
            }
            ServiceParameter parameter = parseServiceParameter(record.payload());
            if (SERVICE_NAME_HANDOVER.equals(parameter.serviceName)) {
                return parameter;
            }
        }
        return null;
    }

    public static boolean announcesNegotiatedHandover(@NonNull byte[] ndefMessage) {
        return findHandoverService(ndefMessage) != null;
    }

    @NonNull
    public static Selected parseHandoverSelect(@NonNull byte[] ndefMessage) {
        NdefMessage message = NdefMessage.parse(ndefMessage);
        NdefRecord hs = message.find(NdefRecord.TNF_WELL_KNOWN, RTD_HANDOVER_SELECT);
        if (hs == null) {
            throw new ProximityException("no Handover Select record in the NDEF message");
        }
        Carriers carriers = carriers(message, hs, BleCarrier.Role.HANDOVER_SELECT);
        if (carriers.ble.isEmpty() && carriers.nfc == null) {
            throw new UnsupportedTransportException(
                    "the Handover Select message carries neither a BLE nor an NFC"
                            + " alternative carrier", null);
        }

        NdefRecord engagement =
                message.findById(NdefRecord.TNF_EXTERNAL_TYPE, TYPE_DEVICE_ENGAGEMENT, ID_MDOC);
        if (engagement == null) {
            engagement = message.find(NdefRecord.TNF_EXTERNAL_TYPE, TYPE_DEVICE_ENGAGEMENT);
        }
        if (engagement == null) {
            throw new ProximityException(
                    "the Handover Select message carries no " + TYPE_DEVICE_ENGAGEMENT + " record");
        }
        return new Selected(carriers.ble.isEmpty() ? null : carriers.ble.get(0),
                carriers.nfc, engagement.payload());
    }

    @NonNull
    public static Requested parseHandoverRequest(@NonNull byte[] ndefMessage) {
        NdefMessage message = NdefMessage.parse(ndefMessage);
        NdefRecord hr = message.find(NdefRecord.TNF_WELL_KNOWN, RTD_HANDOVER_REQUEST);
        if (hr == null) {
            throw new ProximityException("no Handover Request record in the NDEF message");
        }
        Carriers carriers = carriers(message, hr, BleCarrier.Role.HANDOVER_REQUEST);
        if (carriers.ble.isEmpty() && carriers.nfc == null) {
            throw new UnsupportedTransportException(
                    "the Handover Request message carries neither a BLE nor an NFC"
                            + " alternative carrier", null);
        }
        return new Requested(carriers.ble, carriers.nfc, carriers.nfcFirst);
    }

    @NonNull
    private static Carriers carriers(@NonNull NdefMessage message,
                                     @NonNull NdefRecord handover,
                                     @NonNull BleCarrier.Role role) {
        Carriers carriers = new Carriers();
        for (String reference : carrierReferences(handover)) {
            NdefRecord config = message.findById(reference);
            if (config == null) {
                ProximityLogger.getInstance().d("handover: no carrier record for reference "
                        + reference);
                continue;
            }
            if (config.is(NdefRecord.TNF_MIME_MEDIA, BleCarrier.RECORD_TYPE)) {
                carriers.ble.add(BleCarrier.parse(config.payload(), role));
            } else if (config.is(NdefRecord.TNF_EXTERNAL_TYPE, NfcCarrier.RECORD_TYPE)
                    && carriers.nfc == null) {
                carriers.nfc = NfcCarrier.parse(config.payload());
                carriers.nfcFirst = carriers.ble.isEmpty();
            }
        }
        return carriers;
    }

    @NonNull
    private static List<String> carrierReferences(@NonNull NdefRecord handover) {
        byte[] payload = handover.payload();
        if (payload.length < 1) {
            throw new ProximityException("empty handover record");
        }
        if ((payload[0] & 0xFF) >>> 4 != CH_VERSION >>> 4) {
            throw new ProximityException(
                    "unsupported Connection Handover major version " + ((payload[0] & 0xFF) >>> 4));
        }
        NdefMessage embedded =
                NdefMessage.parse(Arrays.copyOfRange(payload, 1, payload.length));

        List<String> references = new ArrayList<>();
        for (NdefRecord record : embedded.records()) {
            if (!record.is(NdefRecord.TNF_WELL_KNOWN, RTD_ALTERNATIVE_CARRIER)) {
                continue;
            }
            references.add(carrierDataReference(record.payload()));
        }
        return references;
    }

    @NonNull
    private static String carrierDataReference(@NonNull byte[] acPayload) {
        if (acPayload.length < 2) {
            throw new ProximityException("malformed alternative carrier record");
        }
        int referenceLength = acPayload[1] & 0xFF;
        if (2 + referenceLength > acPayload.length) {
            throw new ProximityException("malformed alternative carrier record");
        }
        return new String(acPayload, 2, referenceLength, StandardCharsets.UTF_8);
    }

    @NonNull
    private static NdefRecord alternativeCarrier(@NonNull String carrierDataReference,
                                                 @NonNull List<String> auxReferences) {
        byte[] reference = carrierDataReference.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        payload.write(CPS_ACTIVE);
        payload.write(reference.length);
        payload.write(reference, 0, reference.length);
        payload.write(auxReferences.size());
        for (String auxReference : auxReferences) {
            byte[] aux = auxReference.getBytes(StandardCharsets.UTF_8);
            payload.write(aux.length);
            payload.write(aux, 0, aux.length);
        }
        return NdefRecord.of(NdefRecord.TNF_WELL_KNOWN, RTD_ALTERNATIVE_CARRIER, null,
                payload.toByteArray());
    }

    @NonNull
    private static ServiceParameter parseServiceParameter(@NonNull byte[] payload) {
        if (payload.length < 2) {
            throw new ProximityException("malformed TNEP service parameter record");
        }
        int version = payload[0] & 0xFF;
        int nameLength = payload[1] & 0xFF;
        if (2 + nameLength + 5 > payload.length) {
            throw new ProximityException("malformed TNEP service parameter record");
        }
        String name = new String(payload, 2, nameLength, StandardCharsets.UTF_8);
        int offset = 2 + nameLength;
        return new ServiceParameter(version, name,
                payload[offset] & 0xFF,
                payload[offset + 1] & 0xFF,
                payload[offset + 2] & 0xFF,
                ((payload[offset + 3] & 0xFF) << 8) | (payload[offset + 4] & 0xFF));
    }

    @NonNull
    private static byte[] withChVersion(@NonNull byte[] embeddedMessage) {
        byte[] payload = new byte[embeddedMessage.length + 1];
        payload[0] = (byte) CH_VERSION;
        System.arraycopy(embeddedMessage, 0, payload, 1, embeddedMessage.length);
        return payload;
    }
}
