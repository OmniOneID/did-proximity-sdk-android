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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.omnione.did.proximity.sdk.core.ProximityException;
import org.omnione.did.proximity.sdk.core.engagement.BleMode;
import org.omnione.did.proximity.sdk.core.engagement.DeviceEngagement;
import org.omnione.did.proximity.sdk.core.engagement.ModePreference;
import org.omnione.did.proximity.sdk.core.engagement.NfcOptions;
import org.omnione.did.proximity.sdk.core.engagement.TransportMode;
import org.omnione.did.proximity.sdk.core.engagement.TransportPreference;
import org.omnione.did.proximity.sdk.core.engagement.UnsupportedTransportException;
import org.omnione.did.proximity.sdk.core.cbor.CoseKeys;
import org.omnione.did.proximity.sdk.core.session.IsoAnnexD;

import java.security.interfaces.ECPublicKey;
import java.util.EnumSet;
import java.util.UUID;

public class HandoverRequesterTest {

    private static final UUID READER_UUID =
            UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID MDOC_UUID =
            UUID.fromString("3a82d455-d5fc-4db3-bea0-6fd2f6ff32c8");

    private static final byte[] COLLISION = {0x01, 0x02};

    private static byte[] annexDHandoverSelect() {
        return IsoAnnexD.hex(IsoAnnexD.NFC_HANDOVER_SELECT_D_3_3);
    }

    private static byte[] deviceEngagement() {
        return DeviceEngagement.forNfc(IsoAnnexD.publicKey(
                IsoAnnexD.EPHEMERAL_DEVICE_X, IsoAnnexD.EPHEMERAL_DEVICE_Y)).encode();
    }

    private static HandoverRequester requester(ModePreference preference) {
        return HandoverRequester.create(READER_UUID, preference, COLLISION);
    }

    private static HandoverRequester requester(TransportMode... order) {
        return HandoverRequester.create(READER_UUID, TransportPreference.of(order), COLLISION);
    }

    private static byte[] dualSelect() {
        return HandoverMessages.handoverSelect(
                BleCarrier.of(EnumSet.of(BleMode.CENTRAL_CLIENT), MDOC_UUID),
                NfcCarrier.of(NfcOptions.of(65535, 65536)), deviceEngagement());
    }

    @Test
    public void anNfcLedPreferenceTakesTheNfcCarrierAndKeepsTheChannel() {
        FakeType4Tag tag = new FakeType4Tag().serving(dualSelect());

        NfcHandoverResult result = requester(TransportMode.NFC, TransportMode.BLE_CENTRAL_CLIENT)
                .engage(tag);

        assertEquals(TransportMode.NFC, result.selected());
        assertEquals(NfcOptions.of(65535, 65536), result.nfcOptions());
        assertSame(tag, result.channel());
        assertNull(result.mode());
        assertNull(result.serviceUuid());
        assertNull(result.psm());
        assertFalse(result.negotiated());
    }

    @Test
    public void aBleLedPreferenceIgnoresTheNfcCarrier() {
        FakeType4Tag tag = new FakeType4Tag().serving(dualSelect());

        NfcHandoverResult result = requester(TransportMode.BLE_CENTRAL_CLIENT, TransportMode.NFC)
                .engage(tag);

        assertEquals(TransportMode.BLE_CENTRAL_CLIENT, result.selected());
        assertEquals(BleMode.CENTRAL_CLIENT, result.mode());
        assertEquals(MDOC_UUID, result.serviceUuid());
        assertNull(result.nfcOptions());
        assertNull(result.channel());
    }

    @Test
    public void anNfcCarrierWithoutLimitsCannotBeChosen() {
        byte[] select = HandoverMessages.handoverSelect(
                BleCarrier.of(EnumSet.of(BleMode.CENTRAL_CLIENT), MDOC_UUID),
                NfcCarrier.parse(new byte[]{0x01}), deviceEngagement());
        FakeType4Tag tag = new FakeType4Tag().serving(select);

        NfcHandoverResult result = requester(TransportMode.NFC, TransportMode.BLE_CENTRAL_CLIENT)
                .engage(tag);

        assertEquals(TransportMode.BLE_CENTRAL_CLIENT, result.selected());
        assertThrows(UnsupportedTransportException.class,
                () -> requester(TransportMode.NFC).engage(new FakeType4Tag().serving(select)));
    }

    @Test
    public void theSpecPublishedSelectOffersNoNfcForAnNfcOnlyReader() {
        FakeType4Tag tag = new FakeType4Tag().serving(annexDHandoverSelect());

        assertThrows(UnsupportedTransportException.class,
                () -> requester(TransportMode.NFC).engage(tag));
    }

    @Test
    public void theNegotiatedRequestCarriesTheNfcCarrierWithBothLimits() {
        byte[][] seen = new byte[2][];
        FakeType4Tag tag = negotiatedTag(seen, negotiatedSelect(MDOC_UUID, BleMode.CENTRAL_CLIENT));

        requester(TransportMode.BLE_CENTRAL_CLIENT, TransportMode.NFC).engage(tag);

        HandoverMessages.Requested requested = HandoverMessages.parseHandoverRequest(seen[1]);
        assertEquals(1, requested.carriers.size());
        assertNotNull(requested.nfcCarrier);
        assertEquals(NfcOptions.of(65535, 65536), requested.nfcCarrier.options());
        assertFalse(requested.nfcFirst);
        NdefRecord nfc = NdefMessage.parse(seen[1])
                .findById(NdefRecord.TNF_EXTERNAL_TYPE, NfcCarrier.RECORD_TYPE, "nfc");
        assertArrayEquals(IsoAnnexD.hex("010301FFFF0402010000"), nfc.payload());
    }

    @Test
    public void anNfcLedNegotiatedRequestPutsTheNfcCarrierFirstAndTakesAnNfcSelect() {
        byte[][] seen = new byte[2][];
        byte[] nfcSelect = HandoverMessages.handoverSelect(null,
                NfcCarrier.of(NfcOptions.of(255, 256)), deviceEngagement());
        FakeType4Tag tag = negotiatedTag(seen, nfcSelect);

        NfcHandoverResult result = requester(TransportMode.NFC, TransportMode.BLE_PERIPHERAL_SERVER)
                .engage(tag);

        HandoverMessages.Requested requested = HandoverMessages.parseHandoverRequest(seen[1]);
        assertTrue(requested.nfcFirst);
        assertEquals(1, requested.carriers.size());
        assertEquals(TransportMode.NFC, result.selected());
        assertTrue(result.negotiated());
        assertArrayEquals(seen[1], result.handoverRequest());
        assertSame(tag, result.channel());
    }

    @Test
    public void aBleOnlyPreferenceOmitsTheNfcCarrierFromTheRequest() {
        byte[][] seen = new byte[2][];
        FakeType4Tag tag = negotiatedTag(seen, negotiatedSelect(MDOC_UUID, BleMode.CENTRAL_CLIENT));

        requester(ModePreference.CENTRAL_CLIENT_FIRST).engage(tag);

        assertNull(HandoverMessages.parseHandoverRequest(seen[1]).nfcCarrier);
    }

    @Test
    public void anNfcOnlyPreferenceOffersOnlyTheNfcCarrier() {
        byte[][] seen = new byte[2][];
        byte[] nfcSelect = HandoverMessages.handoverSelect(null,
                NfcCarrier.of(NfcOptions.of(255, 256)), deviceEngagement());
        FakeType4Tag tag = negotiatedTag(seen, nfcSelect);

        requester(TransportMode.NFC).engage(tag);

        HandoverMessages.Requested requested = HandoverMessages.parseHandoverRequest(seen[1]);
        assertTrue(requested.carriers.isEmpty());
        assertNotNull(requested.nfcCarrier);
    }

    @Test
    public void staticHandoverReadsTheSpecPublishedHandoverSelect() {
        FakeType4Tag tag = new FakeType4Tag().serving(annexDHandoverSelect());

        NfcHandoverResult result = requester(ModePreference.CENTRAL_CLIENT_FIRST).engage(tag);

        assertFalse(result.negotiated());
        assertNull(result.handoverRequest());
        assertArrayEquals(annexDHandoverSelect(), result.handoverSelect());
        assertEquals(BleMode.CENTRAL_CLIENT, result.mode());
        assertEquals("D.3.3 carries no UUID, so the reader's own UUID stands",
                READER_UUID, result.serviceUuid());
    }

    @Test
    public void theApduSequenceFollowsTheType4TagReadProcedure() {
        FakeType4Tag tag = new FakeType4Tag().serving(annexDHandoverSelect());

        requester(ModePreference.CENTRAL_CLIENT_FIRST).engage(tag);

        assertEquals("00a4040007d276000085010100", tag.commands().get(0));
        assertEquals("00a4000c02e103", tag.commands().get(1));
        assertEquals("00b000000f", tag.commands().get(2));
        assertEquals("00a4000c02e104", tag.commands().get(3));
        assertEquals("00b0000002", tag.commands().get(4));
        assertTrue(tag.commands().get(5).startsWith("00b00002"));
    }

    @Test
    public void aMessageLongerThanOneReadIsReassembled() {
        FakeType4Tag tag = new FakeType4Tag()
                .serving(annexDHandoverSelect())
                .maxTransceiveLength(40);

        NfcHandoverResult result = requester(ModePreference.CENTRAL_CLIENT_FIRST).engage(tag);

        assertArrayEquals(annexDHandoverSelect(), result.handoverSelect());
        long dataReads = tag.commands().stream()
                .filter(c -> c.startsWith("00b0"))
                .filter(c -> !c.equals("00b0000002") && !c.equals("00b000000f"))
                .count();
        assertTrue("195 bytes must need several reads at this transceive limit, saw " + dataReads,
                dataReads > 1);
    }

    @Test
    public void negotiatedHandoverDrivesServiceSelectThenHandoverRequest() {
        byte[][] seen = new byte[2][];
        FakeType4Tag tag = negotiatedTag(seen, negotiatedSelect(MDOC_UUID, BleMode.CENTRAL_CLIENT));

        NfcHandoverResult result = requester(ModePreference.CENTRAL_CLIENT_FIRST).engage(tag);

        assertEquals(HandoverMessages.SERVICE_NAME_HANDOVER,
                HandoverMessages.parseServiceSelect(seen[0]));
        assertTrue(HandoverMessages.isHandoverRequest(seen[1]));
        assertTrue(result.negotiated());
        assertArrayEquals("the transcript must use the exact bytes we wrote",
                seen[1], result.handoverRequest());
        assertEquals(MDOC_UUID, result.serviceUuid());
        assertEquals(BleMode.CENTRAL_CLIENT, result.mode());
    }

    @Test
    public void theHandoverRequestOffersBothBleModes() {
        byte[][] seen = new byte[2][];
        FakeType4Tag tag = negotiatedTag(seen, negotiatedSelect(MDOC_UUID, BleMode.CENTRAL_CLIENT));

        requester(ModePreference.CENTRAL_CLIENT_FIRST).engage(tag);

        HandoverMessages.Requested requested = HandoverMessages.parseHandoverRequest(seen[1]);
        assertEquals(1, requested.carriers.size());
        assertEquals("NFC-E-08: the candidate set is fixed, never narrowed by the setting",
                EnumSet.of(BleMode.CENTRAL_CLIENT, BleMode.PERIPHERAL_SERVER),
                requested.carriers.get(0).modes());
        assertEquals(READER_UUID, requested.carriers.get(0).serviceUuid());
    }

    @Test
    public void aFailedTnepServiceSelectionIsReported() {
        FakeType4Tag tag = new FakeType4Tag()
                .serving(HandoverMessages.initialNegotiatedMessage(0, 15, 0xFFFF))
                .writable(message -> HandoverMessages.tnepStatus(0x01));

        ProximityException error = assertThrows(ProximityException.class,
                () -> requester(ModePreference.CENTRAL_CLIENT_FIRST).engage(tag));
        assertTrue(error.getMessage(), error.getMessage().contains("status 1"));
    }

    @Test
    public void aReadOnlyTagOfferingNegotiatedHandoverIsRejected() {
        FakeType4Tag tag = new FakeType4Tag()
                .serving(HandoverMessages.initialNegotiatedMessage(0, 15, 0xFFFF));

        assertThrows(UnsupportedTransportException.class,
                () -> requester(ModePreference.CENTRAL_CLIENT_FIRST).engage(tag));
    }

    @Test
    public void anInitialMessageThatIsNeitherHandoverNorTnepIsRejected() {
        FakeType4Tag tag = new FakeType4Tag().serving(NdefMessage
                .of(NdefRecord.of(NdefRecord.TNF_WELL_KNOWN, "T", null, new byte[]{0x01}))
                .encode());

        assertThrows(UnsupportedTransportException.class,
                () -> requester(ModePreference.CENTRAL_CLIENT_FIRST).engage(tag));
    }

    @Test
    public void aMissingNdefApplicationIsReportedClearly() {
        FakeType4Tag tag = new FakeType4Tag()
                .serving(annexDHandoverSelect())
                .applicationStatus(Type4Tag.SW_FILE_NOT_FOUND);

        ProximityException error = assertThrows(ProximityException.class,
                () -> requester(ModePreference.CENTRAL_CLIENT_FIRST).engage(tag));
        assertTrue(error.getMessage(), error.getMessage().contains("6A82"));
    }

    @Test
    public void bothBleModesInTheSelectLetTheSettingDecide() {
        byte[] select = negotiatedSelect(MDOC_UUID,
                BleMode.CENTRAL_CLIENT, BleMode.PERIPHERAL_SERVER);

        assertEquals(BleMode.CENTRAL_CLIENT,
                requester(ModePreference.CENTRAL_CLIENT_FIRST)
                        .engage(negotiatedTag(new byte[2][], select)).mode());
        assertEquals(BleMode.PERIPHERAL_SERVER,
                requester(ModePreference.PERIPHERAL_SERVER_FIRST)
                        .engage(negotiatedTag(new byte[2][], select)).mode());
    }

    @Test
    public void aSingleAdvertisedModeWinsOverTheSetting() {
        byte[] select = negotiatedSelect(MDOC_UUID, BleMode.PERIPHERAL_SERVER);

        assertEquals("NFC-E-08: when the mdoc advertises one mode the setting is irrelevant",
                BleMode.PERIPHERAL_SERVER,
                requester(ModePreference.CENTRAL_CLIENT_FIRST)
                        .engage(negotiatedTag(new byte[2][], select)).mode());
    }

    private static byte[] negotiatedSelect(UUID uuid, BleMode... modes) {
        return HandoverMessages.handoverSelect(
                BleCarrier.of(EnumSet.copyOf(java.util.Arrays.asList(modes)), uuid),
                deviceEngagement());
    }

    private static FakeType4Tag negotiatedTag(byte[][] seen, byte[] handoverSelect) {
        FakeType4Tag tag = new FakeType4Tag();
        tag.serving(HandoverMessages.initialNegotiatedMessage(0, 15, 0xFFFF));
        tag.writable(message -> {
            if (HandoverMessages.parseServiceSelect(message) != null) {
                seen[0] = message;
                return HandoverMessages.tnepStatus(HandoverMessages.TNEP_STATUS_SUCCESS);
            }
            seen[1] = message;
            return handoverSelect;
        });
        return tag;
    }

    @Test
    public void theParsedEngagementKeyMatchesTheMdocKey() {
        FakeType4Tag tag = negotiatedTag(new byte[2][],
                negotiatedSelect(MDOC_UUID, BleMode.CENTRAL_CLIENT));

        NfcHandoverResult result = requester(ModePreference.CENTRAL_CLIENT_FIRST).engage(tag);

        ECPublicKey expected = IsoAnnexD.publicKey(
                IsoAnnexD.EPHEMERAL_DEVICE_X, IsoAnnexD.EPHEMERAL_DEVICE_Y);
        assertArrayEquals(CoseKeys.toCoseKey(expected).EncodeToBytes(),
                CoseKeys.toCoseKey(result.eDeviceKey()).EncodeToBytes());
    }

    @Test
    public void aStaticSelectWithAPsmYieldsItWhenPeripheralIsChosen() {
        byte[] select = HandoverMessages.handoverSelect(
                BleCarrier.of(EnumSet.of(BleMode.PERIPHERAL_SERVER), MDOC_UUID).withPsm(192),
                deviceEngagement());
        FakeType4Tag tag = new FakeType4Tag().serving(select);

        NfcHandoverResult result = requester(ModePreference.CENTRAL_CLIENT_FIRST).engage(tag);

        assertEquals(BleMode.PERIPHERAL_SERVER, result.mode());
        assertEquals(Integer.valueOf(192), result.psm());
    }

    @Test
    public void aBothModeSelectWithAPsmDropsItWhenCentralIsChosen() {
        byte[] select = HandoverMessages.handoverSelect(
                BleCarrier.of(EnumSet.of(BleMode.CENTRAL_CLIENT, BleMode.PERIPHERAL_SERVER),
                        MDOC_UUID).withPsm(192),
                deviceEngagement());

        NfcHandoverResult central = requester(ModePreference.CENTRAL_CLIENT_FIRST)
                .engage(new FakeType4Tag().serving(select));
        NfcHandoverResult peripheral = requester(ModePreference.PERIPHERAL_SERVER_FIRST)
                .engage(new FakeType4Tag().serving(select));

        assertEquals(BleMode.CENTRAL_CLIENT, central.mode());
        assertNull(central.psm());
        assertEquals(BleMode.PERIPHERAL_SERVER, peripheral.mode());
        assertEquals(Integer.valueOf(192), peripheral.psm());
    }

    @Test
    public void aNegotiatedSelectWithAPsmYieldsIt() {
        byte[] select = HandoverMessages.handoverSelect(
                BleCarrier.of(EnumSet.of(BleMode.PERIPHERAL_SERVER), MDOC_UUID).withPsm(192),
                deviceEngagement());

        NfcHandoverResult result = requester(ModePreference.CENTRAL_CLIENT_FIRST)
                .engage(negotiatedTag(new byte[2][], select));

        assertEquals(Integer.valueOf(192), result.psm());
        assertTrue(result.negotiated());
    }

    @Test
    public void theSpecPublishedSelectYieldsNoPsm() {
        FakeType4Tag tag = new FakeType4Tag().serving(annexDHandoverSelect());

        assertNull(requester(ModePreference.CENTRAL_CLIENT_FIRST).engage(tag).psm());
    }
}
