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
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

import org.junit.Test;
import org.omnione.did.proximity.sdk.core.ProximityException;
import org.omnione.did.proximity.sdk.core.engagement.BleMode;
import org.omnione.did.proximity.sdk.core.engagement.ModePreference;
import org.omnione.did.proximity.sdk.core.session.IsoAnnexD;

import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

public class BleCarrierTest {

    private static final UUID UUID_VALUE = UUID.fromString("3a82d455-d5fc-4db3-bea0-6fd2f6ff32c8");
    private static final String UUID_LITTLE_ENDIAN = "c832fff6d26fa0beb34dfcd555d4823a";

    private static final Set<BleMode> CENTRAL_ONLY = EnumSet.of(BleMode.CENTRAL_CLIENT);
    private static final Set<BleMode> PERIPHERAL_ONLY = EnumSet.of(BleMode.PERIPHERAL_SERVER);
    private static final Set<BleMode> BOTH =
            EnumSet.of(BleMode.CENTRAL_CLIENT, BleMode.PERIPHERAL_SERVER);

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

    @Test
    public void uuidIsWrittenLittleEndianUnlikeTheQrPath() {
        byte[] oob = BleCarrier.of(CENTRAL_ONLY, UUID_VALUE).toHandoverSelect();

        assertArrayEquals(IsoAnnexD.hex(UUID_LITTLE_ENDIAN),
                java.util.Arrays.copyOfRange(oob, 5, 21));
        assertEquals(UUID_VALUE,
                BleCarrier.parse(oob, BleCarrier.Role.HANDOVER_SELECT).serviceUuid());
    }

    @Test
    public void mdocLeRoleDescribesTheMdocsOwnBleRole() {
        assertEquals(BleCarrier.LE_ROLE_CENTRAL_ONLY,
                leRoleOf(BleCarrier.of(CENTRAL_ONLY, UUID_VALUE).toHandoverSelect()));
        assertEquals(BleCarrier.LE_ROLE_PERIPHERAL_ONLY,
                leRoleOf(BleCarrier.of(PERIPHERAL_ONLY, UUID_VALUE).toHandoverSelect()));
        assertEquals(BleCarrier.LE_ROLE_BOTH_CENTRAL_PREFERRED,
                leRoleOf(BleCarrier.of(BOTH, UUID_VALUE).toHandoverSelect()));
    }

    @Test
    public void readerLeRoleIsTheInverseOfTheMdocMode() {
        assertEquals(BleCarrier.LE_ROLE_PERIPHERAL_ONLY,
                leRoleOf(BleCarrier.of(CENTRAL_ONLY, UUID_VALUE)
                        .toHandoverRequest(ModePreference.CENTRAL_CLIENT_FIRST)));
        assertEquals(BleCarrier.LE_ROLE_CENTRAL_ONLY,
                leRoleOf(BleCarrier.of(PERIPHERAL_ONLY, UUID_VALUE)
                        .toHandoverRequest(ModePreference.PERIPHERAL_SERVER_FIRST)));
    }

    @Test
    public void readerLeRoleForBothModesIsDerivedFromModePreferenceInBothDirections() {
        assertEquals(BleCarrier.LE_ROLE_BOTH_PERIPHERAL_PREFERRED,
                leRoleOf(BleCarrier.of(BOTH, UUID_VALUE)
                        .toHandoverRequest(ModePreference.CENTRAL_CLIENT_FIRST)));
        assertEquals(BleCarrier.LE_ROLE_BOTH_CENTRAL_PREFERRED,
                leRoleOf(BleCarrier.of(BOTH, UUID_VALUE)
                        .toHandoverRequest(ModePreference.PERIPHERAL_SERVER_FIRST)));

        assertNotEquals("a one-directional assertion would not catch the name crossing",
                leRoleOf(BleCarrier.of(BOTH, UUID_VALUE)
                        .toHandoverRequest(ModePreference.CENTRAL_CLIENT_FIRST)),
                leRoleOf(BleCarrier.of(BOTH, UUID_VALUE)
                        .toHandoverRequest(ModePreference.PERIPHERAL_SERVER_FIRST)));
    }

    @Test
    public void theSameLeRoleByteMeansOppositeModesInAnHsAndAnHr() {
        byte[] hs = BleCarrier.of(CENTRAL_ONLY, UUID_VALUE).toHandoverSelect();
        byte[] hr = BleCarrier.of(PERIPHERAL_ONLY, UUID_VALUE)
                .toHandoverRequest(ModePreference.PERIPHERAL_SERVER_FIRST);

        assertEquals(BleCarrier.LE_ROLE_CENTRAL_ONLY, leRoleOf(hs));
        assertEquals(BleCarrier.LE_ROLE_CENTRAL_ONLY, leRoleOf(hr));

        assertEquals(CENTRAL_ONLY, BleCarrier.parse(hs, BleCarrier.Role.HANDOVER_SELECT).modes());
        assertEquals(PERIPHERAL_ONLY, BleCarrier.parse(hr, BleCarrier.Role.HANDOVER_REQUEST).modes());
    }

    @Test
    public void everyLeRoleValueRoundTripsForBothSenders() {
        for (Set<BleMode> modes : new Set[]{CENTRAL_ONLY, PERIPHERAL_ONLY, BOTH}) {
            BleCarrier carrier = BleCarrier.of(modes, UUID_VALUE);

            assertEquals(modes, BleCarrier
                    .parse(carrier.toHandoverSelect(), BleCarrier.Role.HANDOVER_SELECT).modes());
            assertEquals(modes, BleCarrier
                    .parse(carrier.toHandoverRequest(ModePreference.CENTRAL_CLIENT_FIRST),
                            BleCarrier.Role.HANDOVER_REQUEST).modes());
            assertEquals(modes, BleCarrier
                    .parse(carrier.toHandoverRequest(ModePreference.PERIPHERAL_SERVER_FIRST),
                            BleCarrier.Role.HANDOVER_REQUEST).modes());
        }
    }

    @Test
    public void unknownAdStructuresAreSkippedByTheirLength() {
        byte[] withServiceData = IsoAnnexD.hex(
                "021c01"
                        + "051601ff" + "a000"
                        + "1107" + UUID_LITTLE_ENDIAN
                        + "081b28128b37282801");

        BleCarrier carrier = BleCarrier.parse(withServiceData, BleCarrier.Role.HANDOVER_SELECT);

        assertEquals(CENTRAL_ONLY, carrier.modes());
        assertEquals(UUID_VALUE, carrier.serviceUuid());
    }

    @Test
    public void aTruncatedAdStructureIsRejectedWithoutReadingPastTheEnd() {
        byte[] truncated = IsoAnnexD.hex("021c01" + "1107" + "c832fff6d26fa0be");

        assertThrows(ProximityException.class,
                () -> BleCarrier.parse(truncated, BleCarrier.Role.HANDOVER_SELECT));
        assertThrows(ProximityException.class,
                () -> BleCarrier.parse(IsoAnnexD.hex("021c0107"),
                        BleCarrier.Role.HANDOVER_SELECT));
    }

    @Test
    public void aCarrierWithoutLeRoleIsRejected() {
        byte[] uuidOnly = IsoAnnexD.hex("1107" + UUID_LITTLE_ENDIAN);

        ProximityException error = assertThrows(ProximityException.class,
                () -> BleCarrier.parse(uuidOnly, BleCarrier.Role.HANDOVER_SELECT));
        assertEquals("the BLE carrier record carries no LE Role (AD 0x1C)", error.getMessage());
    }

    @Test
    public void anUnsupportedLeRoleValueIsRejected() {
        assertThrows(ProximityException.class,
                () -> BleCarrier.parse(IsoAnnexD.hex("021c04"), BleCarrier.Role.HANDOVER_SELECT));
    }

    @Test
    public void aCarrierWithoutUuidEncodesLeRoleOnly() {
        byte[] oob = BleCarrier.of(CENTRAL_ONLY, null).toHandoverSelect();

        assertArrayEquals(IsoAnnexD.hex("021c01"), oob);
        assertNull(BleCarrier.parse(oob, BleCarrier.Role.HANDOVER_SELECT).serviceUuid());
    }

    @Test
    public void anEmptyModeSetIsRejected() {
        assertThrows(ProximityException.class,
                () -> BleCarrier.of(EnumSet.noneOf(BleMode.class), UUID_VALUE));
    }

    private static byte[] adStructure(byte[] oobData, int adType) {
        int pos = 0;
        while (pos < oobData.length) {
            int length = oobData[pos] & 0xFF;
            if ((oobData[pos + 1] & 0xFF) == adType) {
                return java.util.Arrays.copyOfRange(oobData, pos, pos + 1 + length);
            }
            pos += length + 1;
        }
        return null;
    }

    @Test
    public void thePsmTravelsAsServiceDataUnderTheInterimUuid() {
        byte[] oob = BleCarrier.of(PERIPHERAL_ONLY, UUID_VALUE).withPsm(192).toHandoverSelect();

        assertArrayEquals(IsoAnnexD.hex("071601ffa10018c0"),
                adStructure(oob, BleCarrier.AD_SERVICE_DATA_16));
        assertEquals(Integer.valueOf(192),
                BleCarrier.parse(oob, BleCarrier.Role.HANDOVER_SELECT).psm());
        assertEquals(UUID_VALUE, BleCarrier.parse(oob, BleCarrier.Role.HANDOVER_SELECT).serviceUuid());
    }

    @Test
    public void withoutAPsmNoServiceDataIsWritten() {
        byte[] oob = BleCarrier.of(PERIPHERAL_ONLY, UUID_VALUE).toHandoverSelect();

        assertNull(adStructure(oob, BleCarrier.AD_SERVICE_DATA_16));
        assertNull(BleCarrier.parse(oob, BleCarrier.Role.HANDOVER_SELECT).psm());
        assertNull(BleCarrier.of(PERIPHERAL_ONLY, UUID_VALUE).withPsm(192).withPsm(null).psm());
    }

    @Test
    public void aPsmOnACentralOnlyCarrierIsRejected() {
        assertThrows(ProximityException.class,
                () -> BleCarrier.of(CENTRAL_ONLY, UUID_VALUE).withPsm(192));
        assertThrows(ProximityException.class,
                () -> BleCarrier.of(PERIPHERAL_ONLY, UUID_VALUE).withPsm(0));
    }

    @Test
    public void serviceDataUnderAnotherUuidIsSkipped() {
        byte[] oob = new byte[]{
                0x02, 0x1C, 0x00,
                0x07, 0x16, 0x0F, 0x18, (byte) 0xA1, 0x00, 0x18, (byte) 0xC0};

        BleCarrier parsed = BleCarrier.parse(oob, BleCarrier.Role.HANDOVER_SELECT);

        assertNull(parsed.psm());
        assertEquals(PERIPHERAL_ONLY, parsed.modes());
    }

    @Test
    public void malformedServiceDataIsIgnoredNotFatal() {
        byte[] notAMap = new byte[]{0x02, 0x1C, 0x00, 0x04, 0x16, 0x01, (byte) 0xFF, 0x18};
        byte[] noKeyZero = new byte[]{0x02, 0x1C, 0x00, 0x07, 0x16, 0x01, (byte) 0xFF,
                (byte) 0xA1, 0x01, 0x18, (byte) 0xC0};
        byte[] outOfRange = new byte[]{0x02, 0x1C, 0x00, 0x08, 0x16, 0x01, (byte) 0xFF,
                (byte) 0xA1, 0x00, 0x1A, 0x00, 0x01, 0x00, 0x00};

        assertNull(BleCarrier.parse(notAMap, BleCarrier.Role.HANDOVER_SELECT).psm());
        assertNull(BleCarrier.parse(noKeyZero, BleCarrier.Role.HANDOVER_SELECT).psm());
        assertNull(BleCarrier.parse(outOfRange, BleCarrier.Role.HANDOVER_SELECT).psm());
    }

    @Test
    public void aPsmInAHandoverRequestOrCentralOnlySelectIsDropped() {
        byte[] oob = BleCarrier.of(PERIPHERAL_ONLY, UUID_VALUE).withPsm(192).toHandoverSelect();
        byte[] centralWithData = new byte[]{
                0x02, 0x1C, 0x01,
                0x07, 0x16, 0x01, (byte) 0xFF, (byte) 0xA1, 0x00, 0x18, (byte) 0xC0};

        assertNull(BleCarrier.parse(oob, BleCarrier.Role.HANDOVER_REQUEST).psm());
        assertNull(BleCarrier.parse(centralWithData, BleCarrier.Role.HANDOVER_SELECT).psm());
    }
}
