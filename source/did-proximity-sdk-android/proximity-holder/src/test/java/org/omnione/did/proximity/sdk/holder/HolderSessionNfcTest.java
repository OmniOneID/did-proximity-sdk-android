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

package org.omnione.did.proximity.sdk.holder;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.upokecenter.cbor.CBORObject;

import org.junit.Test;
import org.omnione.did.proximity.sdk.core.ProximityException;
import org.omnione.did.proximity.sdk.core.cbor.CborTags;
import org.omnione.did.proximity.sdk.core.cbor.CoseKeys;
import org.omnione.did.proximity.sdk.core.engagement.BleMode;
import org.omnione.did.proximity.sdk.core.engagement.EngagementCodec;
import org.omnione.did.proximity.sdk.core.engagement.SessionTranscripts;
import org.omnione.did.proximity.sdk.core.nfc.BleCarrier;
import org.omnione.did.proximity.sdk.core.transport.nfc.NfcHolderTransport;
import org.omnione.did.proximity.sdk.core.nfc.NfcCarrier;
import org.omnione.did.proximity.sdk.core.engagement.NfcOptions;
import org.omnione.did.proximity.sdk.core.nfc.HandoverMessages;
import org.omnione.did.proximity.sdk.core.session.SessionCrypto;
import org.omnione.did.proximity.sdk.core.session.SessionKeys;
import org.omnione.did.proximity.sdk.core.engagement.BleTransferMode;
import org.omnione.did.proximity.sdk.core.transport.Transport;
import org.omnione.did.proximity.sdk.core.transport.ble.L2capServer;
import org.omnione.did.proximity.sdk.core.util.NoDeadline;

import java.security.KeyPair;
import java.security.interfaces.ECPublicKey;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

public class HolderSessionNfcTest {

    private static final String MDL = "org.iso.18013.5.1.mDL";
    private static final String NS = "org.iso.18013.5.1";

    private static final class FakeTransport implements Transport {
        final List<byte[]> sent = new ArrayList<>();
        Listener listener;
        boolean stopped;

        @Override public void start(@NonNull Listener listener) {
            this.listener = listener;
        }

        @Override public void send(@NonNull byte[] message) { sent.add(message); }

        @Override public void stop() { stopped = true; }
    }

    private static final class RecordingListener implements HolderSession.Listener {
        ProximityRequest request;
        ProximityException error;
        EngagementMethod method;

        @Override public void onReaderConnected() { }
        @Override public void onRequest(@NonNull ProximityRequest request) {
            this.request = request;
        }
        @Override public void onResponseSent() { }
        @Override public void onError(@NonNull ProximityException error) { this.error = error; }
        @Override public void onTerminated() { }
        @Override public void onEngagementDelivered(@NonNull EngagementMethod method) {
            this.method = method;
        }
    }

    private static byte[] deviceRequestCbor() {
        CBORObject elements = CBORObject.NewMap();
        elements.Add("family_name", true);
        CBORObject namespaces = CBORObject.NewMap();
        namespaces.Add(NS, elements);
        CBORObject items = CBORObject.NewMap();
        items.Add("docType", MDL);
        items.Add("nameSpaces", namespaces);
        CBORObject docRequest = CBORObject.NewMap();
        docRequest.Add("itemsRequest", CborTags.tagged(items.EncodeToBytes()));
        CBORObject requests = CBORObject.NewArray();
        requests.Add(docRequest);
        CBORObject request = CBORObject.NewMap();
        request.Add("version", "1.0");
        request.Add("docRequests", requests);
        return request.EncodeToBytes();
    }

    private static ECPublicKey eDeviceKeyOf(byte[] engagement) {
        return CoseKeys.toPublicKey(CBORObject.DecodeFromBytes(
                CborTags.untag(CBORObject.DecodeFromBytes(engagement)
                        .get(CBORObject.FromObject(1)).get(1))));
    }

    private static byte[] handoverSelect(HolderSession session, UUID uuid) {
        return HandoverMessages.handoverSelect(
                BleCarrier.of(EnumSet.of(BleMode.PERIPHERAL_SERVER), uuid),
                session.nfcEngagementBytes());
    }

    /** Builds the establishment a reader would send if it used {@code transcriptFor}. */
    private static void sendEstablishment(FakeTransport transport,
                                          byte[] qrEngagement,
                                          @Nullable byte[] handoverSelect,
                                          HolderSession session) {
        KeyPair readerPair = CoseKeys.generateEphemeral();
        byte[] readerKeyBytes = CoseKeys
                .toCoseKey((ECPublicKey) readerPair.getPublic()).EncodeToBytes();
        byte[] transcript = handoverSelect == null
                ? SessionTranscripts.forQr(qrEngagement, readerKeyBytes)
                : SessionTranscripts.forNfc(session.nfcEngagementBytes(), readerKeyBytes,
                        handoverSelect, null);
        SessionCrypto readerCrypto = SessionCrypto.forReader(SessionKeys.derive(
                readerPair.getPrivate(), eDeviceKeyOf(qrEngagement), transcript));

        CBORObject establishment = CBORObject.NewMap();
        establishment.Add("eReaderKey", CborTags.tagged(readerKeyBytes));
        establishment.Add("data", readerCrypto.encrypt(deviceRequestCbor()));

        transport.listener.onConnected();
        transport.listener.onMessage(establishment.EncodeToBytes());
    }

    private static HolderSession session(FakeTransport transport) {
        return HolderSession.create(transport, new NoDeadline(), BleMode.PERIPHERAL_SERVER);
    }

    @Test
    public void theNfcEngagementOmitsRetrievalMethodsAndSharesTheQrKey() {
        FakeTransport transport = new FakeTransport();
        HolderSession session = session(transport);

        byte[] qr = EngagementCodec.decode(session.deviceEngagementQr());
        byte[] nfc = session.nfcEngagementBytes();

        assertEquals("NFC engagement must be a two-entry map", 0xA2, nfc[0] & 0xFF);
        assertEquals(3, CBORObject.DecodeFromBytes(qr).size());
        assertEquals(2, CBORObject.DecodeFromBytes(nfc).size());
        assertArrayEquals("both encodings must carry the same EDeviceKey",
                CoseKeys.toCoseKey(eDeviceKeyOf(qr)).EncodeToBytes(),
                CoseKeys.toCoseKey(eDeviceKeyOf(nfc)).EncodeToBytes());
    }

    @Test
    public void aLatchedHandoverMakesEstablishUseTheNfcTranscript() {
        FakeTransport transport = new FakeTransport();
        HolderSession session = session(transport);
        RecordingListener listener = new RecordingListener();
        session.start(listener);

        byte[] qr = EngagementCodec.decode(session.deviceEngagementQr());
        byte[] select = handoverSelect(session, UUID.randomUUID());
        session.onNfcHandoverServed(select, null);

        sendEstablishment(transport, qr, select, session);

        assertNull(listener.error);
        assertNotNull(listener.request);
        assertEquals(EngagementMethod.NFC, listener.method);
        assertArrayEquals(SessionTranscripts.taggedBytes(SessionTranscripts.forNfc(
                        session.nfcEngagementBytes(), readerKeyOf(listener.request), select, null)),
                listener.request.sessionTranscript);
    }

    private static byte[] readerKeyOf(ProximityRequest request) {
        byte[] transcript = CborTags.untag(CBORObject.DecodeFromBytes(request.sessionTranscript));
        return CborTags.untag(CBORObject.DecodeFromBytes(transcript).get(1));
    }

    @Test
    public void withoutALatchEstablishStillUsesTheQrTranscript() {
        FakeTransport transport = new FakeTransport();
        HolderSession session = session(transport);
        RecordingListener listener = new RecordingListener();
        session.start(listener);

        byte[] qr = EngagementCodec.decode(session.deviceEngagementQr());
        sendEstablishment(transport, qr, null, session);

        assertNull(listener.error);
        assertNotNull(listener.request);
        assertEquals(EngagementMethod.QR, listener.method);
        assertArrayEquals(SessionTranscripts.taggedBytes(
                        SessionTranscripts.forQr(qr, readerKeyOf(listener.request))),
                listener.request.sessionTranscript);
    }

    @Test
    public void aStrayLatchFallsBackToTheQrTranscript() {
        FakeTransport transport = new FakeTransport();
        HolderSession session = session(transport);
        RecordingListener listener = new RecordingListener();
        session.start(listener);

        byte[] qr = EngagementCodec.decode(session.deviceEngagementQr());
        session.onNfcHandoverServed(handoverSelect(session, UUID.randomUUID()), null);

        sendEstablishment(transport, qr, null, session);

        assertNull("a reader that used the QR path must still be served", listener.error);
        assertNotNull(listener.request);
        assertEquals(EngagementMethod.QR, listener.method);
        assertArrayEquals(SessionTranscripts.taggedBytes(
                        SessionTranscripts.forQr(qr, readerKeyOf(listener.request))),
                listener.request.sessionTranscript);
    }

    @Test
    public void theCandidateLoopDoesNotTearDownTheTransportBeforeItFinishes() {
        FakeTransport transport = new FakeTransport();
        HolderSession session = session(transport);
        RecordingListener listener = new RecordingListener();
        session.start(listener);

        byte[] qr = EngagementCodec.decode(session.deviceEngagementQr());
        session.onNfcHandoverServed(handoverSelect(session, UUID.randomUUID()), null);

        sendEstablishment(transport, qr, null, session);

        assertFalse("the first failed candidate must not stop the race", transport.stopped);
        assertNotNull(listener.request);
    }

    @Test
    public void anEstablishmentThatNoCandidateDecryptsFails() {
        FakeTransport transport = new FakeTransport();
        HolderSession session = session(transport);
        RecordingListener listener = new RecordingListener();
        session.start(listener);

        KeyPair readerPair = CoseKeys.generateEphemeral();
        byte[] readerKeyBytes = CoseKeys
                .toCoseKey((ECPublicKey) readerPair.getPublic()).EncodeToBytes();
        byte[] wrongTranscript = SessionTranscripts.forQr(
                new byte[]{(byte) 0xA0}, readerKeyBytes);
        SessionCrypto readerCrypto = SessionCrypto.forReader(SessionKeys.derive(
                readerPair.getPrivate(),
                eDeviceKeyOf(EngagementCodec.decode(session.deviceEngagementQr())),
                wrongTranscript));

        CBORObject establishment = CBORObject.NewMap();
        establishment.Add("eReaderKey", CborTags.tagged(readerKeyBytes));
        establishment.Add("data", readerCrypto.encrypt(deviceRequestCbor()));

        transport.listener.onConnected();
        transport.listener.onMessage(establishment.EncodeToBytes());

        assertNotNull(listener.error);
        assertTrue(listener.error.getMessage(),
                listener.error.getMessage().contains("no candidate session transcript"));
        assertNull(listener.request);
    }

    @Test
    public void theTransportFactoryDoesNotArmTheNfcRegistry() {
        FakeTransport transport = new FakeTransport();
        HolderSession session = session(transport);

        assertNull("only a Context-built session serves NFC",
                NfcEngagementRegistry.liveSession());
        assertNotNull(session);
    }

    @Test
    public void latchingCopiesTheHandoverBytes() {
        FakeTransport transport = new FakeTransport();
        HolderSession session = session(transport);
        RecordingListener listener = new RecordingListener();
        session.start(listener);

        byte[] qr = EngagementCodec.decode(session.deviceEngagementQr());
        byte[] select = handoverSelect(session, UUID.randomUUID());
        byte[] mutable = select.clone();
        session.onNfcHandoverServed(mutable, null);
        java.util.Arrays.fill(mutable, (byte) 0x00);

        sendEstablishment(transport, qr, select, session);

        assertNull("the latch must not alias the caller's buffer", listener.error);
        assertEquals(EngagementMethod.NFC, listener.method);
    }

    @Test
    public void everyTapOfTheSameSessionProducesTheSameHandoverSelect() {
        FakeTransport transport = new FakeTransport();
        HolderSession session = session(transport);
        UUID uuid = UUID.randomUUID();

        byte[] first = handoverSelect(session, uuid);
        byte[] second = handoverSelect(session, uuid);

        assertArrayEquals("the mdoc commits to its engagement bytes and UUID at construction, so a"
                        + " second tap re-serves byte-identical bytes and re-latching is a no-op",
                first, second);
    }

    @Test
    public void aSecondTapAfterEstablishmentDoesNotDisturbTheSession() {
        FakeTransport transport = new FakeTransport();
        HolderSession session = session(transport);
        RecordingListener listener = new RecordingListener();
        session.start(listener);

        byte[] qr = EngagementCodec.decode(session.deviceEngagementQr());
        byte[] select = handoverSelect(session, UUID.randomUUID());
        session.onNfcHandoverServed(select, null);
        sendEstablishment(transport, qr, select, session);

        byte[] established = listener.request.sessionTranscript.clone();
        assertEquals(EngagementMethod.NFC, listener.method);

        session.onNfcHandoverServed(handoverSelect(session, UUID.randomUUID()), null);

        assertNull("a tap arriving while the session is live must not fail it", listener.error);
        assertArrayEquals("the transcript is fixed once establishment succeeds",
                established, listener.request.sessionTranscript);

        session.sendResponse(new byte[]{(byte) 0xA0});
        assertNull(listener.error);
        assertTrue("the session must still be able to answer", transport.sent.size() > 0);
    }

    private static class PsmOnlyBuilder implements HolderSession.TransportBuilder {
        final FakeTransport transport = new FakeTransport();

        @NonNull
        @Override
        public Transport centralClient(@NonNull UUID serviceUuid,
                                       @NonNull byte[] expectedIdent) {
            return transport;
        }

        @NonNull
        @Override
        public Transport peripheralServer(@NonNull UUID serviceUuid) {
            return transport;
        }

        @NonNull
        @Override
        public Transport peripheralServer(@NonNull UUID serviceUuid,
                                          @Nullable L2capServer l2cap) {
            return transport;
        }
    }

    private static HolderSession sessionWithPsm(int psm) {
        return HolderSession.create(new NoDeadline(),
                TransferConfig.ble(BleTransferMode.PERIPHERAL_SERVER), null,
                new L2capServer() {
                    @Override
                    public int psm() {
                        return psm;
                    }

                    @Override
                    public void close() {
                    }
                },
                new PsmOnlyBuilder());
    }

    @Test
    public void theRegistryExposesThePsmForTheHandoverSelect() {
        HolderSession session = sessionWithPsm(192);
        UUID uuid = NfcEngagementRegistry.serviceUuids(session).values().iterator().next();

        byte[] select = HandoverMessages.handoverSelect(
                BleCarrier.of(NfcEngagementRegistry.serviceUuids(session).keySet(), uuid)
                        .withPsm(NfcEngagementRegistry.l2capPsm(session)),
                NfcEngagementRegistry.nfcEngagementBytes(session));

        assertEquals(Integer.valueOf(192), NfcEngagementRegistry.l2capPsm(session));
        assertEquals(Integer.valueOf(192),
                HandoverMessages.parseHandoverSelect(select).carrier.psm());
    }

    @Test
    public void theRegistryExposesNfcOptionsForTheHandoverSelect() {
        NfcOptions nfc = NfcOptions.of(0xFFFF, 0x10000);
        HolderSession session = HolderSession.create(new NoDeadline(),
                TransferConfig.ble(BleTransferMode.PERIPHERAL_SERVER), null, null, nfc,
                new PsmOnlyBuilder() {
                    @NonNull
                    @Override
                    public NfcHolderTransport nfc(@NonNull NfcOptions options) {
                        return new NfcHolderTransport(options);
                    }
                });
        UUID uuid = NfcEngagementRegistry.serviceUuids(session).values().iterator().next();

        byte[] select = HandoverMessages.handoverSelect(
                BleCarrier.of(NfcEngagementRegistry.serviceUuids(session).keySet(), uuid),
                NfcCarrier.of(NfcEngagementRegistry.nfcOptions(session)),
                NfcEngagementRegistry.nfcEngagementBytes(session));
        HandoverMessages.Selected selected = HandoverMessages.parseHandoverSelect(select);

        assertEquals(nfc, NfcEngagementRegistry.nfcOptions(session));
        assertNotNull(NfcEngagementRegistry.nfcTransport(session));
        assertNotNull(selected.carrier);
        assertEquals(nfc, selected.nfcCarrier.options());
        assertFalse(NfcEngagementRegistry.isNfcDataSessionLive());
    }

    @Test
    public void aSessionWithoutNfcYieldsNoNfcCarrier() {
        HolderSession session = session(new FakeTransport());

        assertNull(NfcEngagementRegistry.nfcOptions(session));
        assertNull(NfcEngagementRegistry.nfcTransport(session));
        assertNull(NfcCarrier.of(NfcEngagementRegistry.nfcOptions(session)));
    }

    @Test
    public void withoutAnL2capServerTheRegistryExposesNoPsm() {
        HolderSession session = session(new FakeTransport());

        assertNull(NfcEngagementRegistry.l2capPsm(session));
        byte[] select = handoverSelect(session, UUID.randomUUID());
        assertNull(HandoverMessages.parseHandoverSelect(select).carrier.psm());
    }
}
