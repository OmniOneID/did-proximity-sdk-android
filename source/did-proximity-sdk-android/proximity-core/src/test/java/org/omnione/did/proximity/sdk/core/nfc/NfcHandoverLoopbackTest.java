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

import androidx.annotation.NonNull;

import org.junit.Test;
import org.omnione.did.proximity.sdk.core.cbor.CoseKeys;
import org.omnione.did.proximity.sdk.core.engagement.BleMode;
import org.omnione.did.proximity.sdk.core.engagement.DeviceEngagement;
import org.omnione.did.proximity.sdk.core.engagement.ModePreference;
import org.omnione.did.proximity.sdk.core.engagement.SessionTranscripts;
import org.omnione.did.proximity.sdk.core.session.IsoAnnexD;

import java.security.interfaces.ECPublicKey;
import java.util.EnumSet;
import java.util.UUID;

public class NfcHandoverLoopbackTest {

    private static final UUID MDOC_UUID = UUID.fromString("0000a55a-0000-1000-8000-00805f9b34fb");
    private static final UUID READER_UUID = UUID.fromString("0000b66b-0000-1000-8000-00805f9b34fb");
    private static final byte[] COLLISION = {0x01, 0x02};

    private static final String GOLDEN_STATIC_TRANSCRIPT =
            "d81859017c83d8185858a20063312e30018201d818584ba4010220012158205a88d182bce5f4"
            + "2efa59943f33359d2e8a968ff289d93e5fa444b624343167fe225820b16e8cf858ddc7690407"
            + "ba61d4c338237a8cfcf3de6aa672fc60a557aa32fc67d818584ba40102200121582060e33923"
            + "85041f51403051f2415531cb56dd3f999c71687013aac6768bc8187e225820e58deb8fdbe907"
            + "f7dd5368245551a34796f7d2215c440c339bb0f7b67beccdfa8258cc91020f487315d1020961"
            + "6301013001046d646f631a2015016170706c69636174696f6e2f766e642e626c7565746f6f74"
            + "682e6c652e6f6f6230021c031107fb349b5f80000080001000005aa500005c1e580469736f2e"
            + "6f72673a31383031333a646576696365656e676167656d656e746d646f63a20063312e300182"
            + "01d818584ba4010220012158205a88d182bce5f42efa59943f33359d2e8a968ff289d93e5fa4"
            + "44b624343167fe225820b16e8cf858ddc7690407ba61d4c338237a8cfcf3de6aa672fc60a557"
            + "aa32fc67f6";
    private static final String GOLDEN_NEGOTIATED_TRANSCRIPT =
            "d8185901cd83d8185858a20063312e30018201d818584ba4010220012158205a88d182bce5f4"
            + "2efa59943f33359d2e8a968ff289d93e5fa444b624343167fe225820b16e8cf858ddc7690407"
            + "ba61d4c338237a8cfcf3de6aa672fc60a557aa32fc67d818584ba40102200121582060e33923"
            + "85041f51403051f2415531cb56dd3f999c71687013aac6768bc8187e225820e58deb8fdbe907"
            + "f7dd5368245551a34796f7d2215c440c339bb0f7b67beccdfa8258cc91020f487315d1020961"
            + "6301013001046d646f631a2015016170706c69636174696f6e2f766e642e626c7565746f6f74"
            + "682e6c652e6f6f6230021c011107fb349b5f80000080001000005aa500005c1e580469736f2e"
            + "6f72673a31383031333a646576696365656e676167656d656e746d646f63a20063312e300182"
            + "01d818584ba4010220012158205a88d182bce5f42efa59943f33359d2e8a968ff289d93e5fa4"
            + "44b624343167fe225820b16e8cf858ddc7690407ba61d4c338237a8cfcf3de6aa672fc60a557"
            + "aa32fc675850910211487215910202637201025102046163010130005a2015016170706c6963"
            + "6174696f6e2f766e642e626c7565746f6f74682e6c652e6f6f6230021c021107fb349b5f8000"
            + "0080001000006bb60000";

    private static final class Loopback implements ApduChannel {
        private final HandoverResponder responder;

        Loopback(@NonNull HandoverResponder responder) {
            this.responder = responder;
        }

        @NonNull
        @Override
        public byte[] transceive(@NonNull byte[] commandApdu) {
            return responder.processCommandApdu(commandApdu);
        }

        @Override
        public int maxTransceiveLength() {
            return 261;
        }
    }

    private static final class Capture implements HandoverResponder.Listener {
        byte[] handoverSelect;
        byte[] handoverRequest;
        int calls;

        @Override
        public void onHandoverSelectServed(@NonNull byte[] handoverSelect,
                                           byte[] handoverRequest) {
            this.handoverSelect = handoverSelect;
            this.handoverRequest = handoverRequest;
            this.calls++;
        }
    }

    private static byte[] deviceEngagement() {
        return DeviceEngagement.forNfc(IsoAnnexD.publicKey(
                IsoAnnexD.EPHEMERAL_DEVICE_X, IsoAnnexD.EPHEMERAL_DEVICE_Y)).encode();
    }

    private static byte[] eReaderKeyBytes() {
        ECPublicKey reader = IsoAnnexD.publicKey(
                IsoAnnexD.EPHEMERAL_READER_X, IsoAnnexD.EPHEMERAL_READER_Y);
        return CoseKeys.toCoseKey(reader).EncodeToBytes();
    }

    private static HandoverRequester requester(ModePreference preference) {
        return HandoverRequester.create(READER_UUID, preference, COLLISION);
    }

    private static String hex(byte[] value) {
        StringBuilder text = new StringBuilder();
        for (byte b : value) {
            text.append(Character.forDigit((b >> 4) & 0x0F, 16));
            text.append(Character.forDigit(b & 0x0F, 16));
        }
        return text.toString();
    }

    @Test
    public void staticHandoverAgreesOnEveryByteOfTheSessionTranscript() {
        Capture capture = new Capture();
        HandoverResponder responder = HandoverResponder.staticHandover(deviceEngagement(),
                BleCarrier.of(EnumSet.of(BleMode.CENTRAL_CLIENT, BleMode.PERIPHERAL_SERVER),
                        MDOC_UUID),
                capture);

        NfcHandoverResult result =
                requester(ModePreference.CENTRAL_CLIENT_FIRST).engage(new Loopback(responder));

        assertEquals(1, capture.calls);
        assertArrayEquals("the reader must keep the exact bytes the tag served",
                capture.handoverSelect, result.handoverSelect());
        assertNull(capture.handoverRequest);
        assertNull(result.handoverRequest());
        assertFalse(result.negotiated());

        byte[] readerSide = result.sessionTranscript(eReaderKeyBytes());
        byte[] holderSide = SessionTranscripts.forNfc(deviceEngagement(), eReaderKeyBytes(),
                capture.handoverSelect, capture.handoverRequest);

        assertArrayEquals("a one-byte divergence here surfaces only as a decryption failure",
                holderSide, readerSide);
        assertEquals(GOLDEN_STATIC_TRANSCRIPT, hex(SessionTranscripts.taggedBytes(readerSide)));
    }

    @Test
    public void negotiatedHandoverAgreesOnEveryByteOfTheSessionTranscript() {
        Capture capture = new Capture();
        HandoverResponder responder = HandoverResponder.negotiatedHandover(deviceEngagement(),
                EnumSet.of(BleMode.CENTRAL_CLIENT, BleMode.PERIPHERAL_SERVER),
                MDOC_UUID, capture);

        NfcHandoverResult result =
                requester(ModePreference.CENTRAL_CLIENT_FIRST).engage(new Loopback(responder));

        assertEquals(1, capture.calls);
        assertTrue(result.negotiated());
        assertArrayEquals(capture.handoverSelect, result.handoverSelect());
        assertNotNull(capture.handoverRequest);
        assertArrayEquals("the transcript must use the exact Handover Request the reader wrote",
                capture.handoverRequest, result.handoverRequest());

        byte[] readerSide = result.sessionTranscript(eReaderKeyBytes());
        byte[] holderSide = SessionTranscripts.forNfc(deviceEngagement(), eReaderKeyBytes(),
                capture.handoverSelect, capture.handoverRequest);

        assertArrayEquals(holderSide, readerSide);
        assertEquals(GOLDEN_NEGOTIATED_TRANSCRIPT,
                hex(SessionTranscripts.taggedBytes(readerSide)));
    }

    @Test
    public void bothSidesAgreeOnTheCarrierForStaticHandover() {
        Capture capture = new Capture();
        HandoverResponder responder = HandoverResponder.staticHandover(deviceEngagement(),
                BleCarrier.of(EnumSet.of(BleMode.CENTRAL_CLIENT, BleMode.PERIPHERAL_SERVER),
                        MDOC_UUID),
                capture);

        NfcHandoverResult result =
                requester(ModePreference.PERIPHERAL_SERVER_FIRST).engage(new Loopback(responder));

        assertEquals(MDOC_UUID, result.serviceUuid());
        assertEquals(MDOC_UUID, responder.serviceUuid());
        assertTrue("the reader picks from what the mdoc advertised",
                responder.advertisedModes().contains(result.mode()));
        assertEquals("the reader's setting decides when the mdoc advertises both",
                BleMode.PERIPHERAL_SERVER, result.mode());
    }

    @Test
    public void bothSidesAgreeOnTheCarrierForNegotiatedHandover() {
        Capture capture = new Capture();
        HandoverResponder responder = HandoverResponder.negotiatedHandover(deviceEngagement(),
                EnumSet.of(BleMode.CENTRAL_CLIENT, BleMode.PERIPHERAL_SERVER),
                MDOC_UUID, capture);

        NfcHandoverResult result =
                requester(ModePreference.CENTRAL_CLIENT_FIRST).engage(new Loopback(responder));

        assertEquals("in negotiated handover the mdoc makes the final choice",
                responder.selectedMode(), result.mode());
        assertEquals(MDOC_UUID, result.serviceUuid());
    }

    @Test
    public void theMdocKeepsTheSameEngagementBytesOnBothHandoverFlavours() {
        Capture staticCapture = new Capture();
        HandoverResponder staticResponder = HandoverResponder.staticHandover(deviceEngagement(),
                BleCarrier.of(EnumSet.of(BleMode.CENTRAL_CLIENT), MDOC_UUID), staticCapture);
        Capture negotiatedCapture = new Capture();
        HandoverResponder negotiatedResponder = HandoverResponder.negotiatedHandover(
                deviceEngagement(), EnumSet.of(BleMode.CENTRAL_CLIENT), MDOC_UUID,
                negotiatedCapture);

        NfcHandoverResult fromStatic = requester(ModePreference.CENTRAL_CLIENT_FIRST)
                .engage(new Loopback(staticResponder));
        NfcHandoverResult fromNegotiated = requester(ModePreference.CENTRAL_CLIENT_FIRST)
                .engage(new Loopback(negotiatedResponder));

        assertArrayEquals(fromStatic.deviceEngagement(), fromNegotiated.deviceEngagement());
        assertArrayEquals(fromStatic.eDeviceKeyBytes(), fromNegotiated.eDeviceKeyBytes());
    }
}
