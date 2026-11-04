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

package org.omnione.did.proximity.sdk.reader;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import androidx.annotation.NonNull;

import com.upokecenter.cbor.CBORObject;
import com.upokecenter.cbor.CBORType;

import org.junit.Test;
import org.omnione.did.proximity.sdk.core.ProximityException;
import org.omnione.did.proximity.sdk.core.cbor.CborTags;
import org.omnione.did.proximity.sdk.core.engagement.BleMode;
import org.omnione.did.proximity.sdk.core.engagement.ModePreference;
import org.omnione.did.proximity.sdk.core.message.ItemsRequest;
import org.omnione.did.proximity.sdk.core.nfc.ApduChannel;
import org.omnione.did.proximity.sdk.core.nfc.BleCarrier;
import org.omnione.did.proximity.sdk.core.nfc.HandoverRequester;
import org.omnione.did.proximity.sdk.core.nfc.HandoverResponder;
import org.omnione.did.proximity.sdk.core.nfc.NfcHandoverResult;
import org.omnione.did.proximity.sdk.core.transport.Transport;
import org.omnione.did.proximity.sdk.core.util.NoDeadline;
import org.omnione.did.proximity.sdk.holder.EngagementMethod;
import org.omnione.did.proximity.sdk.holder.HolderSession;
import org.omnione.did.proximity.sdk.holder.NfcEngagementRegistry;
import org.omnione.did.proximity.sdk.holder.ProximityRequest;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class ReaderSessionNfcLoopbackTest {

    private static final String DOC_TYPE = "eu.europa.ec.eudi.pid.1";

    private static final class Wire implements Transport {
        private Listener listener;
        private Wire peer;

        @Override public void start(@NonNull Listener listener) {
            this.listener = listener;
        }

        @Override public void send(@NonNull byte[] message) { peer.listener.onMessage(message); }

        @Override public void stop() { }

        void connect() { listener.onConnected(); }
    }

    private static final class TagLoopback implements ApduChannel {
        private final HandoverResponder responder;

        TagLoopback(@NonNull HandoverResponder responder) { this.responder = responder; }

        @NonNull @Override public byte[] transceive(@NonNull byte[] commandApdu) {
            return responder.processCommandApdu(commandApdu);
        }

        @Override public int maxTransceiveLength() { return 261; }
    }

    private static ItemsRequest request() {
        Map<String, Boolean> elements = new LinkedHashMap<>();
        elements.put("family_name", false);
        Map<String, Map<String, Boolean>> nameSpaces = new LinkedHashMap<>();
        nameSpaces.put(DOC_TYPE, elements);
        return ItemsRequest.of(DOC_TYPE, nameSpaces);
    }

    /** Taps the holder over an in-memory Type 4 Tag and returns what the reader learned. */
    private static NfcHandoverResult tap(@NonNull HolderSession holder,
                                         @NonNull BleMode mode,
                                         @NonNull ModePreference preference) {
        Map<BleMode, UUID> uuids = NfcEngagementRegistry.serviceUuids(holder);
        UUID shared = uuids.get(mode);
        HandoverResponder responder = HandoverResponder.staticHandover(
                NfcEngagementRegistry.nfcEngagementBytes(holder),
                BleCarrier.of(EnumSet.of(mode), shared),
                (handoverSelect, handoverRequest) ->
                        NfcEngagementRegistry.latch(holder, handoverSelect, handoverRequest));
        return HandoverRequester.create(UUID.randomUUID(), preference)
                .engage(new TagLoopback(responder));
    }

    @Test
    public void anNfcEngagedSessionRoundTripsAndBothSidesShareOneTranscript() {
        Wire holderWire = new Wire();
        Wire readerWire = new Wire();
        holderWire.peer = readerWire;
        readerWire.peer = holderWire;

        HolderSession holder = HolderSession.create(
                holderWire, new NoDeadline(), BleMode.PERIPHERAL_SERVER);
        List<ProximityRequest> received = new ArrayList<>();
        List<EngagementMethod> methods = new ArrayList<>();
        holder.start(new HolderSession.Listener() {
            @Override public void onReaderConnected() { }
            @Override public void onRequest(@NonNull ProximityRequest request) {
                received.add(request);
                holder.sendResponse(DeviceResponseFixture.of(DOC_TYPE, "family_name", "Kim"));
            }
            @Override public void onResponseSent() { }
            @Override public void onError(@NonNull ProximityException error) { throw error; }
            @Override public void onTerminated() { }
            @Override public void onEngagementDelivered(@NonNull EngagementMethod method) {
                methods.add(method);
            }
        });

        NfcHandoverResult handover =
                tap(holder, BleMode.PERIPHERAL_SERVER, ModePreference.PERIPHERAL_SERVER_FIRST);

        List<ProximityResponse> responses = new ArrayList<>();
        ReaderSession reader = ReaderSession.create(readerWire, new NoDeadline());
        reader.start(handover, request(), new ReaderSession.Listener() {
            @Override public void onMdocConnected() { }
            @Override public void onResponse(@NonNull ProximityResponse response) {
                responses.add(response);
            }
            @Override public void onError(@NonNull ProximityException error) { throw error; }
            @Override public void onTerminated() { }
        });
        readerWire.connect();

        assertEquals(1, received.size());
        assertEquals(1, responses.size());
        assertEquals(EngagementMethod.NFC, methods.get(0));
        assertEquals("Kim", responses.get(0).documents.get(0).flatClaims().get("family_name"));
        assertArrayEquals("holder and reader must agree on every byte of the transcript",
                CborTags.untag(CBORObject.DecodeFromBytes(received.get(0).sessionTranscript)),
                responses.get(0).sessionTranscript);
    }

    @Test
    public void theNfcTranscriptCarriesTheHandoverAndNotAQrNull() {
        Wire holderWire = new Wire();
        Wire readerWire = new Wire();
        holderWire.peer = readerWire;
        readerWire.peer = holderWire;

        HolderSession holder = HolderSession.create(
                holderWire, new NoDeadline(), BleMode.PERIPHERAL_SERVER);
        List<ProximityRequest> received = new ArrayList<>();
        holder.start(new HolderSession.Listener() {
            @Override public void onReaderConnected() { }
            @Override public void onRequest(@NonNull ProximityRequest request) {
                received.add(request);
            }
            @Override public void onResponseSent() { }
            @Override public void onError(@NonNull ProximityException error) { throw error; }
            @Override public void onTerminated() { }
        });

        NfcHandoverResult handover =
                tap(holder, BleMode.PERIPHERAL_SERVER, ModePreference.PERIPHERAL_SERVER_FIRST);

        ReaderSession reader = ReaderSession.create(readerWire, new NoDeadline());
        reader.start(handover, request(), new ReaderSession.Listener() {
            @Override public void onMdocConnected() { }
            @Override public void onResponse(@NonNull ProximityResponse response) { }
            @Override public void onError(@NonNull ProximityException error) { throw error; }
            @Override public void onTerminated() { }
        });
        readerWire.connect();

        CBORObject transcript = CBORObject.DecodeFromBytes(CborTags.untag(
                CBORObject.DecodeFromBytes(received.get(0).sessionTranscript)));
        assertEquals(3, transcript.size());
        assertFalse("a QR transcript would put null here", transcript.get(2).isNull());
        assertEquals(CBORType.Array, transcript.get(2).getType());
        assertEquals(2, transcript.get(2).size());
        assertArrayEquals(handover.handoverSelect(), transcript.get(2).get(0).GetByteString());
        assertTrue("static handover leaves the Handover Request null",
                transcript.get(2).get(1).isNull());
    }

    @Test
    public void theReaderUsesTheCarrierFromTheHandoverNotFromTheEngagement() {
        Wire holderWire = new Wire();
        Wire readerWire = new Wire();
        holderWire.peer = readerWire;
        readerWire.peer = holderWire;

        HolderSession holder = HolderSession.create(
                holderWire, new NoDeadline(), BleMode.CENTRAL_CLIENT);
        holder.start(new HolderSession.Listener() {
            @Override public void onReaderConnected() { }
            @Override public void onRequest(@NonNull ProximityRequest request) { }
            @Override public void onResponseSent() { }
            @Override public void onError(@NonNull ProximityException error) { throw error; }
            @Override public void onTerminated() { }
        });

        NfcHandoverResult handover =
                tap(holder, BleMode.CENTRAL_CLIENT, ModePreference.CENTRAL_CLIENT_FIRST);

        assertEquals(BleMode.CENTRAL_CLIENT, handover.mode());
        assertEquals(NfcEngagementRegistry.serviceUuids(holder).get(BleMode.CENTRAL_CLIENT),
                handover.serviceUuid());
        assertNotNull(handover.eDeviceKeyBytes());
    }
}
