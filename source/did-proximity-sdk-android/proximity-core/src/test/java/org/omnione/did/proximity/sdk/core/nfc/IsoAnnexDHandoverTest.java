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
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.omnione.did.proximity.sdk.core.engagement.BleMode;
import org.omnione.did.proximity.sdk.core.engagement.NfcOptions;
import org.omnione.did.proximity.sdk.core.session.IsoAnnexD;

import java.util.EnumSet;
import java.util.UUID;

public class IsoAnnexDHandoverTest {

    private static final UUID D_3_2_UUID = UUID.fromString("3a82d455-d5fc-4db3-bea0-6fd2f6ff32c8");

    private static byte[] handoverRequest() {
        return IsoAnnexD.hex(IsoAnnexD.NFC_HANDOVER_REQUEST_D_3_2);
    }

    private static byte[] negotiatedHandoverSelect() {
        return IsoAnnexD.hex(IsoAnnexD.NFC_HANDOVER_SELECT_D_3_3);
    }

    @Test
    public void annexD32HandoverRequestReEncodesToTheSameBytes() {
        byte[] published = handoverRequest();

        assertEquals(205, published.length);
        assertArrayEquals(published, NdefMessage.parse(published).encode());
    }

    @Test
    public void annexD33NegotiatedHandoverSelectReEncodesToTheSameBytes() {
        byte[] published = negotiatedHandoverSelect();

        assertEquals(195, published.length);
        assertArrayEquals(published, NdefMessage.parse(published).encode());
    }

    @Test
    public void annexD32CarriesThreeAlternativeCarriersAndOnlyOneIsBle() {
        NdefMessage message = NdefMessage.parse(handoverRequest());

        assertEquals(4, message.records().size());
        assertTrue(message.records().get(0).is(NdefRecord.TNF_WELL_KNOWN, "Hr"));
        assertNotNull(message.findById(NdefRecord.TNF_MIME_MEDIA, BleCarrier.RECORD_TYPE, "0"));
        assertNotNull(message.findById(NdefRecord.TNF_EXTERNAL_TYPE, "iso.org:18013:nfc", "nfc"));
        assertNotNull(message.findById(NdefRecord.TNF_MIME_MEDIA, "application/vnd.wfa.nan", "W"));

        assertEquals(HandoverMessages.CH_VERSION, message.records().get(0).payload()[0] & 0xFF);

        HandoverMessages.Requested requested =
                HandoverMessages.parseHandoverRequest(handoverRequest());

        assertEquals("the nfc and Wi-Fi Aware carriers must be skipped, not rejected",
                1, requested.carriers.size());
        assertEquals(D_3_2_UUID, requested.carriers.get(0).serviceUuid());
        assertEquals("LE Role 0x02 from a reader means both modes, mdoc central client preferred",
                EnumSet.of(BleMode.CENTRAL_CLIENT, BleMode.PERIPHERAL_SERVER),
                requested.carriers.get(0).modes());
        assertNotNull("the nfc carrier is parsed, not skipped", requested.nfcCarrier);
        assertNull("D.3.2 carries only the version byte", requested.nfcCarrier.options());
        assertFalse("the BLE ac precedes the nfc ac in D.3.2", requested.nfcFirst);
    }

    @Test
    public void ourHandoverSelectWithBleAndNfcReparsesBothCarriersBleFirst() {
        BleCarrier carrier = BleCarrier.of(EnumSet.of(BleMode.CENTRAL_CLIENT), D_3_2_UUID);
        NfcCarrier nfc = NfcCarrier.readerCarrier(NfcOptions.of(65535, 65536));
        byte[] deviceEngagement = {(byte) 0xA2, 0x00, 0x63};

        byte[] ours = HandoverMessages.handoverSelect(carrier, nfc, deviceEngagement);
        NdefMessage message = NdefMessage.parse(ours);
        HandoverMessages.Selected reparsed = HandoverMessages.parseHandoverSelect(ours);

        assertArrayEquals(ours, message.encode());
        assertEquals(4, message.records().size());
        assertTrue(message.records().get(1).is(NdefRecord.TNF_MIME_MEDIA, BleCarrier.RECORD_TYPE));
        assertTrue(message.records().get(2).is(NdefRecord.TNF_EXTERNAL_TYPE, NfcCarrier.RECORD_TYPE));
        assertTrue(message.records().get(2).hasId("nfc"));
        assertNotNull(reparsed.carrier);
        assertEquals(D_3_2_UUID, reparsed.carrier.serviceUuid());
        assertNotNull(reparsed.nfcCarrier);
        assertEquals(NfcOptions.of(65535, 65536), reparsed.nfcCarrier.options());
        assertArrayEquals(deviceEngagement, reparsed.deviceEngagement);
    }

    @Test
    public void aBleOnlyHandoverSelectIsByteIdenticalWithTheThreeArgOverload() {
        BleCarrier carrier = BleCarrier.of(EnumSet.of(BleMode.CENTRAL_CLIENT), D_3_2_UUID);
        byte[] deviceEngagement = {(byte) 0xA2, 0x00, 0x63};

        assertArrayEquals(HandoverMessages.handoverSelect(carrier, deviceEngagement),
                HandoverMessages.handoverSelect(carrier, null, deviceEngagement));
    }

    @Test
    public void anNfcOnlyHandoverSelectParsesWithoutABleCarrier() {
        NfcCarrier nfc = NfcCarrier.readerCarrier(NfcOptions.of(255, 256));
        byte[] deviceEngagement = {(byte) 0xA2, 0x00, 0x63};

        HandoverMessages.Selected reparsed = HandoverMessages.parseHandoverSelect(
                HandoverMessages.handoverSelect(null, nfc, deviceEngagement));

        assertNull(reparsed.carrier);
        assertEquals(NfcOptions.of(255, 256), reparsed.nfcCarrier.options());
    }

    @Test
    public void annexD33IsANegotiatedSelectWhereTheMdocChoseCentralClientMode() {
        HandoverMessages.Selected selected =
                HandoverMessages.parseHandoverSelect(negotiatedHandoverSelect());

        assertEquals("LE Role 0x01 from an mdoc means mdoc central client mode",
                EnumSet.of(BleMode.CENTRAL_CLIENT), selected.carrier.modes());
        assertNull("D.3.3 omits the 128-bit UUID AD structure; the reader's Hr supplied it",
                selected.carrier.serviceUuid());
        assertEquals(0xA2, selected.deviceEngagement[0] & 0xFF);
        assertEquals(88, selected.deviceEngagement.length);
    }

    @Test
    public void annexDLeRolePairAgreesOnMdocCentralClientMode() {
        byte[] readerOob = NdefMessage.parse(handoverRequest())
                .findById(NdefRecord.TNF_MIME_MEDIA, BleCarrier.RECORD_TYPE, "0").payload();
        byte[] mdocOob = NdefMessage.parse(negotiatedHandoverSelect())
                .findById(NdefRecord.TNF_MIME_MEDIA, BleCarrier.RECORD_TYPE, "0").payload();

        assertEquals(BleCarrier.LE_ROLE_BOTH_PERIPHERAL_PREFERRED, leRoleOf(readerOob));
        assertEquals(BleCarrier.LE_ROLE_CENTRAL_ONLY, leRoleOf(mdocOob));

        assertTrue(BleCarrier.parse(readerOob, BleCarrier.Role.HANDOVER_REQUEST)
                .supports(BleMode.CENTRAL_CLIENT));
        assertEquals(EnumSet.of(BleMode.CENTRAL_CLIENT),
                BleCarrier.parse(mdocOob, BleCarrier.Role.HANDOVER_SELECT).modes());
    }

    @Test
    public void oobPayloadStartsDirectlyWithAnAdStructure() {
        byte[] mdocOob = NdefMessage.parse(negotiatedHandoverSelect())
                .findById(NdefRecord.TNF_MIME_MEDIA, BleCarrier.RECORD_TYPE, "0").payload();

        assertEquals(12, mdocOob.length);
        assertEquals("first byte is the AD length, not an OOB block length",
                0x08, mdocOob[0] & 0xFF);
        assertEquals(BleCarrier.AD_DEVICE_ADDRESS, mdocOob[1] & 0xFF);
    }

    @Test
    public void ourStaticHandoverSelectIsNotTheAnnexDExample() {
        BleCarrier carrier = BleCarrier.of(
                EnumSet.of(BleMode.CENTRAL_CLIENT, BleMode.PERIPHERAL_SERVER), D_3_2_UUID);
        byte[] deviceEngagement = {(byte) 0xA2, 0x00, 0x63};

        byte[] ours = HandoverMessages.handoverSelect(carrier, deviceEngagement);
        HandoverMessages.Selected reparsed = HandoverMessages.parseHandoverSelect(ours);

        assertArrayEquals(ours, NdefMessage.parse(ours).encode());
        assertEquals(EnumSet.of(BleMode.CENTRAL_CLIENT, BleMode.PERIPHERAL_SERVER),
                reparsed.carrier.modes());
        assertEquals("our static Hs always carries the UUID; Annex D has no static Hs example",
                D_3_2_UUID, reparsed.carrier.serviceUuid());
        assertArrayEquals(deviceEngagement, reparsed.deviceEngagement);
        assertEquals(BleCarrier.LE_ROLE_BOTH_CENTRAL_PREFERRED,
                leRoleOf(NdefMessage.parse(ours)
                        .findById(NdefRecord.TNF_MIME_MEDIA, BleCarrier.RECORD_TYPE, "0")
                        .payload()));
    }

    private static int leRoleOf(byte[] oobData) {
        int pos = 0;
        while (pos < oobData.length) {
            int length = oobData[pos] & 0xFF;
            if ((oobData[pos + 1] & 0xFF) == BleCarrier.AD_LE_ROLE) {
                return oobData[pos + 2] & 0xFF;
            }
            pos += length + 1;
        }
        throw new AssertionError("no LE Role AD structure");
    }
}
