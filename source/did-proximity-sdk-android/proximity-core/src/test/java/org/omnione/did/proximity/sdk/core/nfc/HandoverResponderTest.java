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
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import androidx.annotation.NonNull;

import org.junit.Test;
import org.omnione.did.proximity.sdk.core.ProximityException;
import org.omnione.did.proximity.sdk.core.engagement.BleMode;
import org.omnione.did.proximity.sdk.core.engagement.DeviceEngagement;
import org.omnione.did.proximity.sdk.core.engagement.ModePreference;
import org.omnione.did.proximity.sdk.core.engagement.NfcOptions;
import org.omnione.did.proximity.sdk.core.engagement.TransportMode;
import org.omnione.did.proximity.sdk.core.session.IsoAnnexD;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.UUID;

public class HandoverResponderTest {

    private static final UUID MDOC_UUID = UUID.fromString("0000a55a-0000-1000-8000-00805f9b34fb");
    private static final UUID READER_UUID = UUID.fromString("0000b66b-0000-1000-8000-00805f9b34fb");

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

    private static HandoverResponder staticResponder(Capture capture, BleMode... modes) {
        return HandoverResponder.staticHandover(deviceEngagement(),
                BleCarrier.of(EnumSet.copyOf(Arrays.asList(modes)), MDOC_UUID), capture);
    }

    private static HandoverResponder negotiatedResponder(Capture capture) {
        return HandoverResponder.negotiatedHandover(deviceEngagement(),
                EnumSet.of(BleMode.CENTRAL_CLIENT, BleMode.PERIPHERAL_SERVER),
                MDOC_UUID, capture);
    }

    private static final NfcOptions MDOC_NFC = NfcOptions.of(65535, 65536);
    private static final NfcOptions READER_NFC = NfcOptions.of(65535, 65536);

    private static HandoverResponder negotiatedNfcResponder(Capture capture) {
        return HandoverResponder.negotiatedHandover(deviceEngagement(),
                EnumSet.of(BleMode.CENTRAL_CLIENT, BleMode.PERIPHERAL_SERVER),
                MDOC_UUID, null, MDOC_NFC, capture);
    }

    private static byte[] negotiateTo(HandoverResponder responder, byte[] request) {
        selectNdefFile(responder);
        readWholeNdefFile(responder);
        writeNdefMessage(responder,
                HandoverMessages.serviceSelect(HandoverMessages.SERVICE_NAME_HANDOVER));
        readWholeNdefFile(responder);
        assertEquals(Type4Tag.SW_SUCCESS, status(writeNdefMessage(responder, request)));
        return readWholeNdefFile(responder);
    }

    private static byte[] readerRequest(boolean withBle, boolean withNfc, boolean nfcFirst) {
        return HandoverMessages.handoverRequest(
                withBle ? BleCarrier.of(EnumSet.of(BleMode.CENTRAL_CLIENT), READER_UUID) : null,
                ModePreference.CENTRAL_CLIENT_FIRST, new byte[]{0x01, 0x02}, null,
                withNfc ? NfcCarrier.readerCarrier(READER_NFC) : null, nfcFirst);
    }

    @Test
    public void aStaticSelectListsTheBleCarrierBeforeTheNfcCarrier() {
        Capture capture = new Capture();
        HandoverResponder responder = HandoverResponder.staticHandover(deviceEngagement(),
                BleCarrier.of(EnumSet.of(BleMode.CENTRAL_CLIENT), MDOC_UUID),
                NfcCarrier.of(MDOC_NFC), capture);
        selectNdefFile(responder);

        byte[] select = readWholeNdefFile(responder);
        HandoverMessages.Selected selected = HandoverMessages.parseHandoverSelect(select);

        assertEquals(MDOC_UUID, selected.carrier.serviceUuid());
        assertEquals(MDOC_NFC, selected.nfcCarrier.options());
        NdefMessage message = NdefMessage.parse(select);
        assertTrue(message.records().get(1).is(NdefRecord.TNF_MIME_MEDIA, BleCarrier.RECORD_TYPE));
        assertTrue(message.records().get(2).is(NdefRecord.TNF_EXTERNAL_TYPE, NfcCarrier.RECORD_TYPE));
        assertArrayEquals(select, responder.staticHandoverSelect());
    }

    @Test
    public void aStaticSelectWithoutNfcOptionsIsUnchanged() {
        Capture capture = new Capture();
        HandoverResponder plain = staticResponder(capture, BleMode.CENTRAL_CLIENT);
        HandoverResponder explicit = HandoverResponder.staticHandover(deviceEngagement(),
                BleCarrier.of(EnumSet.of(BleMode.CENTRAL_CLIENT), MDOC_UUID),
                NfcCarrier.of(null), capture);

        assertArrayEquals(plain.staticHandoverSelect(), explicit.staticHandoverSelect());
        assertNull(HandoverMessages.parseHandoverSelect(plain.staticHandoverSelect()).nfcCarrier);
    }

    @Test
    public void aNegotiatedRequestWithNfcFirstIsAnsweredWithAnNfcOnlySelect() {
        Capture capture = new Capture();
        HandoverResponder responder = negotiatedNfcResponder(capture);

        byte[] select = negotiateTo(responder, readerRequest(true, true, true));
        HandoverMessages.Selected selected = HandoverMessages.parseHandoverSelect(select);

        assertEquals(TransportMode.NFC, responder.selectedTransport());
        assertNull(responder.selectedMode());
        assertNull(selected.carrier);
        assertEquals(MDOC_NFC, selected.nfcCarrier.options());
        assertEquals(1, capture.calls);
    }

    @Test
    public void aNegotiatedRequestWithBleFirstKeepsTheBleChoice() {
        Capture capture = new Capture();
        HandoverResponder responder = negotiatedNfcResponder(capture);

        byte[] select = negotiateTo(responder, readerRequest(true, true, false));
        HandoverMessages.Selected selected = HandoverMessages.parseHandoverSelect(select);

        assertEquals(TransportMode.BLE_CENTRAL_CLIENT, responder.selectedTransport());
        assertEquals(BleMode.CENTRAL_CLIENT, responder.selectedMode());
        assertNull(selected.nfcCarrier);
        assertEquals(MDOC_UUID, selected.carrier.serviceUuid());
    }

    @Test
    public void anNfcOnlyRequestIsAnsweredWithNfcWhenTheMdocOffersIt() {
        Capture capture = new Capture();
        HandoverResponder responder = negotiatedNfcResponder(capture);

        byte[] select = negotiateTo(responder, readerRequest(false, true, false));

        assertEquals(TransportMode.NFC, responder.selectedTransport());
        assertNull(HandoverMessages.parseHandoverSelect(select).carrier);
    }

    @Test
    public void anNfcRequestToAnMdocWithoutNfcFallsBackToBle() {
        Capture capture = new Capture();
        HandoverResponder responder = negotiatedResponder(capture);

        byte[] select = negotiateTo(responder, readerRequest(true, true, true));

        assertEquals(TransportMode.BLE_CENTRAL_CLIENT, responder.selectedTransport());
        assertNull(HandoverMessages.parseHandoverSelect(select).nfcCarrier);
    }

    @Test
    public void anNfcOnlyRequestToAnMdocWithoutNfcIsRefused() {
        Capture capture = new Capture();
        HandoverResponder responder = negotiatedResponder(capture);
        selectNdefFile(responder);
        readWholeNdefFile(responder);
        writeNdefMessage(responder,
                HandoverMessages.serviceSelect(HandoverMessages.SERVICE_NAME_HANDOVER));
        readWholeNdefFile(responder);

        assertNotEquals(Type4Tag.SW_SUCCESS,
                status(writeNdefMessage(responder, readerRequest(false, true, false))));
        assertEquals(0, capture.calls);
    }

    @Test
    public void deactivationClearsTheSelectedTransport() {
        Capture capture = new Capture();
        HandoverResponder responder = negotiatedNfcResponder(capture);
        negotiateTo(responder, readerRequest(true, true, true));

        responder.onDeactivated();

        assertNull(responder.selectedTransport());
        assertNull(responder.selectedMode());
    }

    private static int status(byte[] response) {
        return Apdu.parseResponse(response).status;
    }

    private static byte[] data(byte[] response) {
        return Apdu.parseResponse(response).data;
    }

    private static byte[] selectNdefFile(HandoverResponder responder) {
        responder.processCommandApdu(Apdu.selectApplication(Type4Tag.NDEF_APPLICATION_ID));
        return responder.processCommandApdu(Apdu.selectFile(Type4Tag.FILE_ID_NDEF));
    }

    private static byte[] readWholeNdefFile(HandoverResponder responder) {
        byte[] nlen = data(responder.processCommandApdu(Apdu.readBinary(0, 2)));
        int length = ((nlen[0] & 0xFF) << 8) | (nlen[1] & 0xFF);
        return data(responder.processCommandApdu(Apdu.readBinary(2, length)));
    }

    private static byte[] writeNdefMessage(HandoverResponder responder, byte[] message) {
        responder.processCommandApdu(Apdu.updateBinary(0, new byte[]{0, 0}));
        responder.processCommandApdu(Apdu.updateBinary(2, message));
        return responder.processCommandApdu(Apdu.updateBinary(0,
                new byte[]{(byte) (message.length >>> 8), (byte) message.length}));
    }

    @Test
    public void staticCapabilityContainerIsReadOnlyAndNegotiatedIsWritable() {
        Capture capture = new Capture();
        byte[] readOnly = capabilityContainer(staticResponder(capture, BleMode.CENTRAL_CLIENT));
        byte[] writable = capabilityContainer(negotiatedResponder(capture));

        assertEquals(Type4Tag.WRITE_ACCESS_DENIED, readOnly[14] & 0xFF);
        assertEquals(Type4Tag.WRITE_ACCESS_GRANTED, writable[14] & 0xFF);
        assertNotEquals("write access is functional, not decorative",
                readOnly[14], writable[14]);
    }

    private static byte[] capabilityContainer(HandoverResponder responder) {
        responder.processCommandApdu(Apdu.selectApplication(Type4Tag.NDEF_APPLICATION_ID));
        responder.processCommandApdu(Apdu.selectFile(Type4Tag.FILE_ID_CAPABILITY_CONTAINER));
        return data(responder.processCommandApdu(
                Apdu.readBinary(0, Type4Tag.CAPABILITY_CONTAINER_LENGTH)));
    }

    @Test
    public void readingTheNdefFileTwiceReturnsIdenticalBytes() {
        Capture capture = new Capture();
        HandoverResponder responder = staticResponder(capture, BleMode.CENTRAL_CLIENT);
        selectNdefFile(responder);

        byte[] first = readWholeNdefFile(responder);
        byte[] second = readWholeNdefFile(responder);

        assertArrayEquals("readers legitimately re-read the file", first, second);
    }

    @Test
    public void theStaticListenerFiresOnceWhenTheWholeMessageHasBeenRead() {
        Capture capture = new Capture();
        HandoverResponder responder = staticResponder(capture, BleMode.CENTRAL_CLIENT);
        selectNdefFile(responder);

        assertEquals(0, capture.calls);
        byte[] served = readWholeNdefFile(responder);

        assertEquals(1, capture.calls);
        assertArrayEquals(served, capture.handoverSelect);
        assertNull(capture.handoverRequest);
        assertTrue(responder.servedHandoverSelect());

        readWholeNdefFile(responder);
        assertEquals("a re-read must not fire the latch again", 1, capture.calls);
    }

    @Test
    public void updateBinaryIsRefusedOnAStaticResponder() {
        Capture capture = new Capture();
        HandoverResponder responder = staticResponder(capture, BleMode.CENTRAL_CLIENT);
        selectNdefFile(responder);

        assertEquals(Type4Tag.SW_WRONG_PARAMETERS,
                status(responder.processCommandApdu(Apdu.updateBinary(0, new byte[]{0, 0}))));
    }

    @Test
    public void negotiatedHandoverWalksTheTnepStateMachine() {
        Capture capture = new Capture();
        HandoverResponder responder = negotiatedResponder(capture);
        selectNdefFile(responder);

        byte[] initial = readWholeNdefFile(responder);
        assertTrue(HandoverMessages.announcesNegotiatedHandover(initial));

        assertEquals(Type4Tag.SW_SUCCESS, status(writeNdefMessage(responder,
                HandoverMessages.serviceSelect(HandoverMessages.SERVICE_NAME_HANDOVER))));
        assertEquals(HandoverMessages.TNEP_STATUS_SUCCESS,
                HandoverMessages.parseTnepStatus(readWholeNdefFile(responder)));

        byte[] request = HandoverMessages.handoverRequest(
                BleCarrier.of(EnumSet.of(BleMode.CENTRAL_CLIENT, BleMode.PERIPHERAL_SERVER),
                        READER_UUID),
                ModePreference.CENTRAL_CLIENT_FIRST, new byte[]{0x01, 0x02}, null);
        assertEquals(Type4Tag.SW_SUCCESS, status(writeNdefMessage(responder, request)));

        byte[] select = readWholeNdefFile(responder);
        assertEquals(1, capture.calls);
        assertArrayEquals(select, capture.handoverSelect);
        assertArrayEquals(request, capture.handoverRequest);
        assertEquals(BleMode.CENTRAL_CLIENT, responder.selectedMode());
        assertEquals(1, HandoverMessages.parseHandoverSelect(select).carrier.modes().size());
    }

    @Test
    public void aBodyWriteWithoutTheLeadingNlenResetIsRejected() {
        Capture capture = new Capture();
        HandoverResponder responder = negotiatedResponder(capture);
        selectNdefFile(responder);

        assertEquals(Type4Tag.SW_WRONG_PARAMETERS,
                status(responder.processCommandApdu(Apdu.updateBinary(2, new byte[]{0x01}))));
        assertEquals(0, capture.calls);
    }

    @Test
    public void anUnknownServiceSelectResetsToTheInitialMessage() {
        Capture capture = new Capture();
        HandoverResponder responder = negotiatedResponder(capture);
        selectNdefFile(responder);
        byte[] initial = readWholeNdefFile(responder);

        assertEquals(Type4Tag.SW_WRONG_PARAMETERS, status(writeNdefMessage(responder,
                HandoverMessages.serviceSelect("urn:nfc:sn:something-else"))));

        assertArrayEquals("the tag must fall back to its initial message",
                initial, readWholeNdefFile(responder));
        assertEquals(0, capture.calls);
    }

    @Test
    public void aDeactivationMidWriteLeavesTheNextTapClean() {
        Capture capture = new Capture();
        HandoverResponder responder = negotiatedResponder(capture);
        selectNdefFile(responder);
        byte[] initial = readWholeNdefFile(responder);

        responder.processCommandApdu(Apdu.updateBinary(0, new byte[]{0, 0}));
        responder.processCommandApdu(Apdu.updateBinary(2, new byte[]{0x01, 0x02}));
        responder.onDeactivated();

        assertFalse(responder.servedHandoverSelect());
        selectNdefFile(responder);
        assertArrayEquals(initial, readWholeNdefFile(responder));

        assertEquals(Type4Tag.SW_WRONG_PARAMETERS,
                status(responder.processCommandApdu(Apdu.updateBinary(2, new byte[]{0x03}))));
    }

    @Test
    public void selectingAnUnknownFileIsRefused() {
        Capture capture = new Capture();
        HandoverResponder responder = staticResponder(capture, BleMode.CENTRAL_CLIENT);
        responder.processCommandApdu(Apdu.selectApplication(Type4Tag.NDEF_APPLICATION_ID));

        assertEquals(Type4Tag.SW_FILE_NOT_FOUND,
                status(responder.processCommandApdu(Apdu.selectFile(0xE105))));
    }

    @Test
    public void aForeignApplicationIdIsRefused() {
        Capture capture = new Capture();
        HandoverResponder responder = staticResponder(capture, BleMode.CENTRAL_CLIENT);

        assertEquals(Type4Tag.SW_FILE_NOT_FOUND, status(responder.processCommandApdu(
                Apdu.selectApplication(new byte[]{(byte) 0xA0, 0x00, 0x00, 0x02, 0x48, 0x04, 0x00}))));
    }

    @Test
    public void readingBeforeSelectingTheApplicationIsRefused() {
        Capture capture = new Capture();
        HandoverResponder responder = staticResponder(capture, BleMode.CENTRAL_CLIENT);

        assertEquals(Type4Tag.SW_FILE_NOT_FOUND,
                status(responder.processCommandApdu(Apdu.selectFile(Type4Tag.FILE_ID_NDEF))));
        assertEquals(Type4Tag.SW_FILE_NOT_FOUND,
                status(responder.processCommandApdu(Apdu.readBinary(0, 2))));
    }

    @Test
    public void theNegotiatedSelectNeverEchoesTheReadersUuidBlindly() {
        Capture capture = new Capture();
        HandoverResponder responder = negotiatedResponder(capture);
        selectNdefFile(responder);
        readWholeNdefFile(responder);
        writeNdefMessage(responder, HandoverMessages.serviceSelect(
                HandoverMessages.SERVICE_NAME_HANDOVER));
        readWholeNdefFile(responder);

        writeNdefMessage(responder, HandoverMessages.handoverRequest(
                BleCarrier.of(EnumSet.of(BleMode.CENTRAL_CLIENT), READER_UUID),
                ModePreference.CENTRAL_CLIENT_FIRST, new byte[]{0x01, 0x02}, null));

        HandoverMessages.Selected selected =
                HandoverMessages.parseHandoverSelect(readWholeNdefFile(responder));
        assertEquals("the mdoc answers with its own UUID, not the reader's",
                MDOC_UUID, selected.carrier.serviceUuid());
    }

    @Test
    public void anExtendedLengthReadBinaryIsAnswered() {
        Capture capture = new Capture();
        HandoverResponder responder = staticResponder(capture, BleMode.CENTRAL_CLIENT);
        selectNdefFile(responder);

        byte[] nlen = data(responder.processCommandApdu(Apdu.readBinary(0, 2)));
        int length = ((nlen[0] & 0xFF) << 8) | (nlen[1] & 0xFF);

        byte[] extended = {
                (byte) Type4Tag.CLA, (byte) Type4Tag.INS_READ_BINARY,
                0x00, 0x02,
                0x00, (byte) (length >>> 8), (byte) length
        };
        byte[] response = responder.processCommandApdu(extended);

        assertEquals("a reader using extended Le must be answered, not rejected with 6B00",
                Type4Tag.SW_SUCCESS, status(response));
        assertEquals(length, data(response).length);
        assertArrayEquals(readWholeNdefFile(responder), data(response));
    }

    @Test
    public void extendedLengthCommandApdusParseInEveryCase() {
        Apdu.Command onlyLe = Apdu.parseCommand(new byte[]{
                0x00, (byte) 0xB0, 0x00, 0x02, 0x00, 0x00, (byte) 0xCC});
        assertEquals(0x00CC, onlyLe.le);
        assertEquals(0, onlyLe.data.length);
        assertEquals(0x0002, onlyLe.offset());

        Apdu.Command withData = Apdu.parseCommand(new byte[]{
                0x00, (byte) 0xD6, 0x00, 0x02, 0x00, 0x00, 0x03, 0x0A, 0x0B, 0x0C});
        assertArrayEquals(new byte[]{0x0A, 0x0B, 0x0C}, withData.data);
        assertEquals(0, withData.le);

        Apdu.Command withDataAndLe = Apdu.parseCommand(new byte[]{
                0x00, (byte) 0xD6, 0x00, 0x02, 0x00, 0x00, 0x02, 0x0A, 0x0B, 0x00, (byte) 0x10});
        assertArrayEquals(new byte[]{0x0A, 0x0B}, withDataAndLe.data);
        assertEquals(0x0010, withDataAndLe.le);

        assertEquals("Le of 0x0000 in extended form means 65536",
                65536, Apdu.parseCommand(new byte[]{
                        0x00, (byte) 0xB0, 0x00, 0x00, 0x00, 0x00, 0x00}).le);
    }

    @Test
    public void aTruncatedExtendedLengthApduIsRejected() {
        assertThrows(org.omnione.did.proximity.sdk.core.ProximityException.class,
                () -> Apdu.parseCommand(new byte[]{0x00, (byte) 0xB0, 0x00, 0x02, 0x00, 0x00}));
        assertThrows(org.omnione.did.proximity.sdk.core.ProximityException.class,
                () -> Apdu.parseCommand(new byte[]{
                        0x00, (byte) 0xD6, 0x00, 0x02, 0x00, 0x00, 0x05, 0x0A}));
    }

    @Test
    public void anExtendedLengthUpdateBinaryDrivesTheTnepStateMachine() {
        Capture capture = new Capture();
        HandoverResponder responder = negotiatedResponder(capture);
        selectNdefFile(responder);
        readWholeNdefFile(responder);

        byte[] select = HandoverMessages.serviceSelect(HandoverMessages.SERVICE_NAME_HANDOVER);
        responder.processCommandApdu(extendedUpdate(0, new byte[]{0, 0}));
        responder.processCommandApdu(extendedUpdate(2, select));
        byte[] commit = responder.processCommandApdu(extendedUpdate(0,
                new byte[]{(byte) (select.length >>> 8), (byte) select.length}));

        assertEquals(Type4Tag.SW_SUCCESS, status(commit));
        assertEquals(HandoverMessages.TNEP_STATUS_SUCCESS,
                HandoverMessages.parseTnepStatus(readWholeNdefFile(responder)));
    }

    private static byte[] extendedUpdate(int offset, byte[] payload) {
        byte[] apdu = new byte[7 + payload.length];
        apdu[0] = (byte) Type4Tag.CLA;
        apdu[1] = (byte) Type4Tag.INS_UPDATE_BINARY;
        apdu[2] = (byte) (offset >>> 8);
        apdu[3] = (byte) offset;
        apdu[4] = 0x00;
        apdu[5] = (byte) (payload.length >>> 8);
        apdu[6] = (byte) payload.length;
        System.arraycopy(payload, 0, apdu, 7, payload.length);
        return apdu;
    }

    @Test
    public void aStaticHandoverSelectCarriesThePsmAsServiceData() {
        Capture capture = new Capture();
        HandoverResponder responder = HandoverResponder.staticHandover(deviceEngagement(),
                BleCarrier.of(EnumSet.of(BleMode.PERIPHERAL_SERVER), MDOC_UUID).withPsm(192),
                capture);
        selectNdefFile(responder);

        byte[] select = readWholeNdefFile(responder);

        assertEquals(Integer.valueOf(192),
                HandoverMessages.parseHandoverSelect(select).carrier.psm());
    }

    @Test
    public void aNegotiatedSelectCarriesThePsmOnlyWhenPeripheralIsChosen() {
        Capture capture = new Capture();
        HandoverResponder responder = HandoverResponder.negotiatedHandover(deviceEngagement(),
                EnumSet.of(BleMode.CENTRAL_CLIENT, BleMode.PERIPHERAL_SERVER),
                MDOC_UUID, 192, capture);
        selectNdefFile(responder);
        readWholeNdefFile(responder);
        writeNdefMessage(responder,
                HandoverMessages.serviceSelect(HandoverMessages.SERVICE_NAME_HANDOVER));
        readWholeNdefFile(responder);

        writeNdefMessage(responder, HandoverMessages.handoverRequest(
                BleCarrier.of(EnumSet.of(BleMode.CENTRAL_CLIENT), READER_UUID),
                ModePreference.CENTRAL_CLIENT_FIRST, new byte[]{0x01, 0x02}, null));

        assertEquals(BleMode.CENTRAL_CLIENT, responder.selectedMode());
        assertNull(HandoverMessages.parseHandoverSelect(readWholeNdefFile(responder))
                .carrier.psm());

        responder.onDeactivated();
        selectNdefFile(responder);
        readWholeNdefFile(responder);
        writeNdefMessage(responder,
                HandoverMessages.serviceSelect(HandoverMessages.SERVICE_NAME_HANDOVER));
        readWholeNdefFile(responder);
        writeNdefMessage(responder, HandoverMessages.handoverRequest(
                BleCarrier.of(EnumSet.of(BleMode.PERIPHERAL_SERVER), READER_UUID),
                ModePreference.PERIPHERAL_SERVER_FIRST, new byte[]{0x01, 0x02}, null));

        assertEquals(BleMode.PERIPHERAL_SERVER, responder.selectedMode());
        assertEquals(Integer.valueOf(192),
                HandoverMessages.parseHandoverSelect(readWholeNdefFile(responder))
                        .carrier.psm());
    }

    @Test
    public void aNegotiatedPsmWithoutPeripheralSupportIsRejected() {
        assertThrows(ProximityException.class, () -> HandoverResponder.negotiatedHandover(
                deviceEngagement(), EnumSet.of(BleMode.CENTRAL_CLIENT), MDOC_UUID, 192,
                new Capture()));
    }
}
