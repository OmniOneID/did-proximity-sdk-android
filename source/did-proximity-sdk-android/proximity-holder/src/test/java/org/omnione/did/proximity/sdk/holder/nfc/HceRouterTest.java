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

package org.omnione.did.proximity.sdk.holder.nfc;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import androidx.annotation.NonNull;

import org.junit.Test;
import org.omnione.did.proximity.sdk.core.ProximityException;
import org.omnione.did.proximity.sdk.core.cbor.CoseKeys;
import org.omnione.did.proximity.sdk.core.engagement.BleMode;
import org.omnione.did.proximity.sdk.core.engagement.DeviceEngagement;
import org.omnione.did.proximity.sdk.core.engagement.NfcOptions;
import org.omnione.did.proximity.sdk.core.nfc.Apdu;
import org.omnione.did.proximity.sdk.core.nfc.BleCarrier;
import org.omnione.did.proximity.sdk.core.nfc.HandoverResponder;
import org.omnione.did.proximity.sdk.core.nfc.NfcCarrier;
import org.omnione.did.proximity.sdk.core.nfc.Type4Tag;
import org.omnione.did.proximity.sdk.core.transport.Transport;
import org.omnione.did.proximity.sdk.core.transport.nfc.EnvelopeFraming;
import org.omnione.did.proximity.sdk.core.transport.nfc.NfcHolderTransport;

import java.security.interfaces.ECPublicKey;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

public class HceRouterTest {

    private static final NfcOptions LIMITS = NfcOptions.of(65535, 65536);
    private static final byte[] SELECT_NDEF = hex("00A4040007D276000085010100");

    private static final class Events implements Transport.Listener {
        final List<String> events = new ArrayList<>();

        @Override public void onConnected() { events.add("connected"); }
        @Override public void onMessage(@NonNull byte[] message) { events.add("message"); }
        @Override public void onError(@NonNull ProximityException error) { events.add("error"); }
        @Override public void onDisconnected() { events.add("disconnected"); }
    }

    private static HandoverResponder responder() {
        byte[] engagement = DeviceEngagement.forNfc(
                (ECPublicKey) CoseKeys.generateEphemeral().getPublic()).encode();
        return HandoverResponder.staticHandover(engagement,
                BleCarrier.of(EnumSet.of(BleMode.CENTRAL_CLIENT), UUID.randomUUID()),
                NfcCarrier.of(LIMITS), (hs, hr) -> { });
    }

    private static byte[] hex(String text) {
        byte[] out = new byte[text.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(text.substring(2 * i, 2 * i + 2), 16);
        }
        return out;
    }

    private static int status(byte[] response) {
        return Apdu.parseResponse(response).status;
    }

    private static byte[] selectData(int p2) {
        byte[] apdu = EnvelopeFraming.selectMdocApplication();
        apdu[3] = (byte) p2;
        return apdu;
    }

    private static final NfcHolderTransport.ResponseSink NO_SINK = response -> {
        throw new AssertionError("nothing should be deferred here");
    };

    @Test
    public void theNdefAidRoutesToTheHandoverResponder() {
        HandoverResponder responder = responder();
        HceRouter router = new HceRouter(() -> responder, () -> null);

        assertEquals(Type4Tag.SW_SUCCESS, status(router.processCommandApdu(SELECT_NDEF, NO_SINK)));
        assertEquals(Type4Tag.SW_SUCCESS, status(router.processCommandApdu(
                hex("00A4000C02E103"), NO_SINK)));
        byte[] container = router.processCommandApdu(hex("00B000000F"), NO_SINK);
        assertEquals(15, Apdu.parseResponse(container).data.length);
    }

    @Test
    public void theDataAidRoutesToTheTransportWithEitherP2() {
        for (int p2 : new int[]{0x00, 0x0C}) {
            NfcHolderTransport transport = new NfcHolderTransport(LIMITS);
            Events events = new Events();
            transport.start(events);
            HceRouter router = new HceRouter(HceRouterTest::responder, () -> transport);

            byte[] response = router.processCommandApdu(selectData(p2), NO_SINK);

            assertArrayEquals(hex("9000"), response);
            assertEquals(List.of("connected"), events.events);
            assertTrue(transport.isLinkActive());
        }
    }

    @Test
    public void theDataAidWithoutATransportIsNotFound() {
        HceRouter router = new HceRouter(HceRouterTest::responder, () -> null);

        assertEquals(Type4Tag.SW_FILE_NOT_FOUND,
                status(router.processCommandApdu(selectData(0x0C), NO_SINK)));
    }

    @Test
    public void anUnknownAidIsNotFoundAndLeavesTheRouteAlone() {
        NfcHolderTransport transport = new NfcHolderTransport(LIMITS);
        transport.start(new Events());
        HceRouter router = new HceRouter(HceRouterTest::responder, () -> transport);
        router.processCommandApdu(selectData(0x0C), NO_SINK);

        assertEquals(Type4Tag.SW_FILE_NOT_FOUND, status(router.processCommandApdu(
                hex("00A4040007A0000000041010"), NO_SINK)));
        assertEquals(Type4Tag.SW_SUCCESS, status(router.processCommandApdu(
                EnvelopeFraming.envelope(false, new byte[]{0x53}, 256, false), NO_SINK)));
    }

    @Test
    public void nonSelectCommandsFollowTheActiveAidAndDefaultToHandover() {
        HandoverResponder responder = responder();
        NfcHolderTransport transport = new NfcHolderTransport(LIMITS);
        transport.start(new Events());
        HceRouter router = new HceRouter(() -> responder, () -> transport);

        assertEquals("before any SELECT the responder answers (and refuses)",
                Type4Tag.SW_SUCCESS != status(router.processCommandApdu(
                        hex("00B000000F"), NO_SINK)), true);

        router.processCommandApdu(selectData(0x0C), NO_SINK);
        assertEquals(Type4Tag.SW_SUCCESS, status(router.processCommandApdu(
                EnvelopeFraming.envelope(false, new byte[]{0x53}, 256, false), NO_SINK)));

        router.processCommandApdu(SELECT_NDEF, NO_SINK);
        assertEquals(Type4Tag.SW_SUCCESS, status(router.processCommandApdu(
                hex("00A4000C02E103"), NO_SINK)));
    }

    @Test
    public void withoutALiveSessionEverythingIsNotFound() {
        HceRouter router = new HceRouter(() -> null, () -> null);

        assertEquals(Type4Tag.SW_FILE_NOT_FOUND,
                status(router.processCommandApdu(SELECT_NDEF, NO_SINK)));
        assertEquals(Type4Tag.SW_FILE_NOT_FOUND,
                status(router.processCommandApdu(hex("00B000000F"), NO_SINK)));
    }

    @Test
    public void deactivationReachesBothSidesAndResetsTheRoute() {
        HandoverResponder responder = responder();
        NfcHolderTransport transport = new NfcHolderTransport(LIMITS);
        Events events = new Events();
        transport.start(events);
        HceRouter router = new HceRouter(() -> responder, () -> transport);
        router.processCommandApdu(SELECT_NDEF, NO_SINK);
        router.processCommandApdu(hex("00A4000C02E104"), NO_SINK);
        router.processCommandApdu(selectData(0x0C), NO_SINK);

        router.onDeactivated();

        assertEquals(List.of("connected", "disconnected"), events.events);
        assertNull(responder.selectedTransport());
        assertEquals("the NDEF file selection did not survive deactivation",
                Type4Tag.SW_SUCCESS != status(router.processCommandApdu(
                        hex("00B0000002"), NO_SINK)), true);
    }
}
