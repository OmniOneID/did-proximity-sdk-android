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

package org.omnione.did.proximity.sdk.core.engagement;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.upokecenter.cbor.CBORObject;
import com.upokecenter.cbor.CBORType;

import org.junit.Test;
import org.omnione.did.proximity.sdk.core.cbor.CborTags;
import org.omnione.did.proximity.sdk.core.cbor.CoseKeys;
import org.omnione.did.proximity.sdk.core.session.SessionKeys;

import java.security.KeyPair;
import java.security.interfaces.ECPublicKey;

public class SessionTranscriptsTest {

    @Test
    public void qrTranscriptHasThreeElementsWithNullHandover() {
        byte[] engagement = new byte[]{(byte) 0xA0};
        byte[] readerKey = CoseKeys
                .toCoseKey((ECPublicKey) CoseKeys.generateEphemeral().getPublic())
                .EncodeToBytes();

        CBORObject transcript = CBORObject.DecodeFromBytes(
                SessionTranscripts.forQr(engagement, readerKey));

        assertEquals(3, transcript.size());
        assertTrue(transcript.get(2).isNull());
    }

    @Test
    public void bothBytesElementsAreTagged24() {
        byte[] engagement = new byte[]{(byte) 0xA0};
        byte[] readerKey = new byte[]{(byte) 0xA1};

        CBORObject transcript = CBORObject.DecodeFromBytes(
                SessionTranscripts.forQr(engagement, readerKey));

        assertEquals(CborTags.ENCODED_CBOR, transcript.get(0).getMostOuterTag().ToInt32Checked());
        assertEquals(CborTags.ENCODED_CBOR, transcript.get(1).getMostOuterTag().ToInt32Checked());
        assertArrayEquals(engagement, CborTags.untag(transcript.get(0)));
        assertArrayEquals(readerKey, CborTags.untag(transcript.get(1)));
    }

    @Test
    public void taggedBytesWrapsWholeTranscript() {
        byte[] transcript = SessionTranscripts.forQr(
                new byte[]{(byte) 0xA0}, new byte[]{(byte) 0xA1});

        CBORObject wrapped = CBORObject.DecodeFromBytes(
                SessionTranscripts.taggedBytes(transcript));

        assertEquals(CborTags.ENCODED_CBOR, wrapped.getMostOuterTag().ToInt32Checked());
        assertArrayEquals(transcript, CborTags.untag(wrapped));
    }

    @Test
    public void staticHandoverTranscriptCarriesTheHandoverSelectAndANullRequest() {
        byte[] engagement = {(byte) 0xA2};
        byte[] readerKey = {(byte) 0xA1};
        byte[] handoverSelect = {0x11, 0x22, 0x33};

        CBORObject transcript = CBORObject.DecodeFromBytes(
                SessionTranscripts.forNfc(engagement, readerKey, handoverSelect, null));

        assertEquals(3, transcript.size());
        CBORObject handover = transcript.get(2);
        assertEquals(CBORType.Array, handover.getType());
        assertEquals(2, handover.size());
        assertArrayEquals(handoverSelect, handover.get(0).GetByteString());
        assertTrue(handover.get(1).isNull());
    }

    @Test
    public void negotiatedHandoverTranscriptCarriesBothMessages() {
        byte[] handoverSelect = {0x11, 0x22};
        byte[] handoverRequest = {0x44, 0x55, 0x66};

        CBORObject handover = CBORObject.DecodeFromBytes(SessionTranscripts.forNfc(
                new byte[]{(byte) 0xA2}, new byte[]{(byte) 0xA1},
                handoverSelect, handoverRequest)).get(2);

        assertArrayEquals(handoverSelect, handover.get(0).GetByteString());
        assertArrayEquals(handoverRequest, handover.get(1).GetByteString());
    }

    @Test
    public void theNfcHandoverElementIsNotTagged24() {
        CBORObject transcript = CBORObject.DecodeFromBytes(SessionTranscripts.forNfc(
                new byte[]{(byte) 0xA2}, new byte[]{(byte) 0xA1},
                new byte[]{0x11}, new byte[]{0x22}));

        assertTrue(transcript.get(0).HasMostOuterTag(CborTags.ENCODED_CBOR));
        assertTrue(transcript.get(1).HasMostOuterTag(CborTags.ENCODED_CBOR));
        assertFalse("NDEF messages are raw bytes, not embedded CBOR",
                transcript.get(2).isTagged());
        assertFalse(transcript.get(2).get(0).isTagged());
    }

    @Test
    public void nfcAndQrTranscriptsDifferOnlyInTheHandoverElement() {
        byte[] engagement = {(byte) 0xA2};
        byte[] readerKey = {(byte) 0xA1};

        CBORObject qr = CBORObject.DecodeFromBytes(
                SessionTranscripts.forQr(engagement, readerKey));
        CBORObject nfc = CBORObject.DecodeFromBytes(
                SessionTranscripts.forNfc(engagement, readerKey, new byte[]{0x11}, null));

        assertArrayEquals(qr.get(0).EncodeToBytes(), nfc.get(0).EncodeToBytes());
        assertArrayEquals(qr.get(1).EncodeToBytes(), nfc.get(1).EncodeToBytes());
        assertTrue(qr.get(2).isNull());
        assertEquals(CBORType.Array, nfc.get(2).getType());
    }

    @Test
    public void taggedBytesOfTheQrTranscriptUntagsBackToTheDeriveInput() {
        byte[] transcript = SessionTranscripts.forQr(
                new byte[]{(byte) 0xA0}, new byte[]{(byte) 0xA1});

        byte[] handedOver = SessionTranscripts.taggedBytes(transcript);
        CBORObject wrapped = CBORObject.DecodeFromBytes(handedOver);

        assertEquals(CborTags.ENCODED_CBOR, wrapped.getMostOuterTag().ToInt32Checked());
        assertEquals(CBORType.ByteString, wrapped.getType());
        assertArrayEquals(transcript, CborTags.untag(wrapped));
        assertEquals(CBORType.Array, CBORObject.DecodeFromBytes(CborTags.untag(wrapped)).getType());
    }

    @Test
    public void keysDerivedFromTheUntaggedHandoverMatchTheBareTranscript() {
        byte[] transcript = SessionTranscripts.forQr(
                new byte[]{(byte) 0xA0}, new byte[]{(byte) 0xA1});
        KeyPair device = CoseKeys.generateEphemeral();
        KeyPair reader = CoseKeys.generateEphemeral();

        SessionKeys fromBare = SessionKeys.derive(
                device.getPrivate(), (ECPublicKey) reader.getPublic(), transcript);
        SessionKeys fromHandover = SessionKeys.derive(
                device.getPrivate(), (ECPublicKey) reader.getPublic(),
                CborTags.untag(CBORObject.DecodeFromBytes(
                        SessionTranscripts.taggedBytes(transcript))));

        assertArrayEquals(fromBare.deviceKey(), fromHandover.deviceKey());
        assertArrayEquals(fromBare.readerKey(), fromHandover.readerKey());
    }
}
