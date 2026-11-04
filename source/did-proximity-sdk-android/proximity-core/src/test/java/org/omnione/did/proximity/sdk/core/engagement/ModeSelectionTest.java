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
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;

import com.upokecenter.cbor.CBORObject;

import org.junit.Test;
import org.omnione.did.proximity.sdk.core.ProximityException;
import org.omnione.did.proximity.sdk.core.cbor.CborTags;
import org.omnione.did.proximity.sdk.core.cbor.CoseKeys;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.interfaces.ECPublicKey;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class ModeSelectionTest {

    private static final UUID PERIPHERAL = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID CENTRAL = UUID.fromString("66666666-7777-8888-9999-aaaaaaaaaaaa");

    @Test
    public void peripheralOnlyEngagementSelectsPeripheralServer() {
        DeviceEngagement engagement = DeviceEngagement.parse(
                encode(true, false), ModePreference.PERIPHERAL_SERVER_FIRST);

        assertEquals(BleMode.PERIPHERAL_SERVER, engagement.mode());
        assertEquals(PERIPHERAL, engagement.serviceUuid());
    }

    @Test
    public void centralOnlyEngagementSelectsCentralClient() {
        DeviceEngagement engagement = DeviceEngagement.parse(
                encode(false, true), ModePreference.PERIPHERAL_SERVER_FIRST);

        assertEquals(BleMode.CENTRAL_CLIENT, engagement.mode());
        assertEquals(CENTRAL, engagement.serviceUuid());
    }

    @Test
    public void bothModesHonourThePeripheralFirstPreference() {
        DeviceEngagement engagement = DeviceEngagement.parse(
                encode(true, true), ModePreference.PERIPHERAL_SERVER_FIRST);

        assertEquals(BleMode.PERIPHERAL_SERVER, engagement.mode());
        assertEquals(PERIPHERAL, engagement.serviceUuid());
    }

    @Test
    public void bothModesHonourTheCentralFirstPreference() {
        DeviceEngagement engagement = DeviceEngagement.parse(
                encode(true, true), ModePreference.CENTRAL_CLIENT_FIRST);

        assertEquals(BleMode.CENTRAL_CLIENT, engagement.mode());
        assertEquals(CENTRAL, engagement.serviceUuid());
    }

    @Test
    public void theDefaultPreferenceIsCentralClientFirst() {
        assertEquals(BleMode.CENTRAL_CLIENT, DeviceEngagement.parse(encode(true, true)).mode());
        assertEquals(CENTRAL, DeviceEngagement.parse(encode(true, true)).serviceUuid());
    }

    @Test
    public void neitherModeIsAnUnsupportedTransport() {
        assertThrows(UnsupportedTransportException.class,
                () -> DeviceEngagement.parse(encode(false, false)));
    }

    @Test
    public void eDeviceKeyBytesAreTheTaggedItemFromTheEngagement() {
        byte[] encoded = encode(true, false);
        byte[] expected = CBORObject.DecodeFromBytes(encoded)
                .get(CBORObject.FromObject(1)).get(1).EncodeToBytes();

        assertArrayEquals(expected, DeviceEngagement.parse(encoded).eDeviceKeyBytes());
    }

    @Test
    public void forQrRoundTripsThroughParse() {
        ECPublicKey key = (ECPublicKey) CoseKeys.generateEphemeral().getPublic();
        byte[] encoded = DeviceEngagement.forQr(key, PERIPHERAL).encode();

        DeviceEngagement parsed = DeviceEngagement.parse(encoded);

        assertEquals(BleMode.PERIPHERAL_SERVER, parsed.mode());
        assertEquals(PERIPHERAL, parsed.serviceUuid());
        assertEquals(key.getW().getAffineX(), parsed.eDeviceKey().getW().getAffineX());
    }

    @Test
    public void forQrWithCentralClientModeRoundTripsThroughParse() {
        ECPublicKey key = (ECPublicKey) CoseKeys.generateEphemeral().getPublic();

        DeviceEngagement parsed = DeviceEngagement.parse(
                DeviceEngagement.forQr(key, CENTRAL, BleMode.CENTRAL_CLIENT).encode());

        assertEquals(BleMode.CENTRAL_CLIENT, parsed.mode());
        assertEquals(CENTRAL, parsed.serviceUuid());
    }

    @Test
    public void forQrWithoutAModeStaysPeripheralServer() {
        ECPublicKey key = (ECPublicKey) CoseKeys.generateEphemeral().getPublic();

        assertEquals(BleMode.PERIPHERAL_SERVER,
                DeviceEngagement.parse(DeviceEngagement.forQr(key, PERIPHERAL).encode()).mode());
    }

    @Test
    public void eDeviceKeyBytesMatchTheCapturedEngagementByteForByte() {
        byte[] expected = hex("d818584ba4010220012158200a6ab654be85f59456608c903bdf34e7"
                + "c64d8fd9fbc8948f2faabecabd767c82225820b0fc54ee092a66cf8d63f39c37c3108"
                + "c5f57fd7af8f110a9327415e013bc39ba");

        assertArrayEquals(expected, DeviceEngagement.parse(run14()).eDeviceKeyBytes());
    }

    private static byte[] hex(String value) {
        byte[] out = new byte[value.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    private static byte[] run14() {
        try (InputStream stream = ModeSelectionTest.class
                .getResourceAsStream("/multipaz-run14-central-client-engagement.hex")) {
            assertNotNull(stream);
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int read;
            while ((read = stream.read(chunk)) != -1) {
                buffer.write(chunk, 0, read);
            }
            String text = new String(buffer.toByteArray(), StandardCharsets.US_ASCII).trim();
            byte[] out = new byte[text.length() / 2];
            for (int i = 0; i < out.length; i++) {
                out[i] = (byte) Integer.parseInt(text.substring(i * 2, i * 2 + 2), 16);
            }
            return out;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] encode(boolean peripheralServer, boolean centralClient) {
        ECPublicKey key = (ECPublicKey) CoseKeys.generateEphemeral().getPublic();

        CBORObject security = CBORObject.NewArray();
        security.Add(1);
        security.Add(CborTags.tagged(CoseKeys.toCoseKey(key).EncodeToBytes()));

        CBORObject options = CBORObject.NewMap();
        options.Add(0, peripheralServer);
        options.Add(1, centralClient);
        options.Add(10, bytes(PERIPHERAL));
        options.Add(11, bytes(CENTRAL));

        CBORObject method = CBORObject.NewArray();
        method.Add(2);
        method.Add(1);
        method.Add(options);

        CBORObject methods = CBORObject.NewArray();
        methods.Add(method);

        CBORObject engagement = CBORObject.NewMap();
        engagement.Add(0, "1.0");
        engagement.Add(1, security);
        engagement.Add(2, methods);
        return engagement.EncodeToBytes();
    }

    @Test
    public void aMalformedMethodDoesNotHideAUsableOne() {
        CBORObject broken = CBORObject.NewArray();
        broken.Add(2);
        broken.Add(1);
        CBORObject brokenOptions = CBORObject.NewMap();
        brokenOptions.Add(0, false);
        brokenOptions.Add(1, true);
        brokenOptions.Add(11, new byte[16]);
        brokenOptions.Add(21, 192);
        broken.Add(brokenOptions);

        CBORObject methods = CBORObject.NewArray();
        methods.Add(broken);
        methods.Add(CBORObject.DecodeFromBytes(
                CBORObject.DecodeFromBytes(encode(true, false))
                        .get(CBORObject.FromObject(2)).get(0).EncodeToBytes()));

        CBORObject engagement = CBORObject.DecodeFromBytes(encode(true, false));
        engagement.Set(CBORObject.FromObject(2), methods);

        DeviceEngagement parsed = DeviceEngagement.parse(
                engagement.EncodeToBytes(), ModePreference.PERIPHERAL_SERVER_FIRST);

        assertEquals(BleMode.PERIPHERAL_SERVER, parsed.mode());
    }

    private static byte[] bytes(UUID uuid) {
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(16);
        buffer.putLong(uuid.getMostSignificantBits());
        buffer.putLong(uuid.getLeastSignificantBits());
        return buffer.array();
    }

    @Test
    public void bothModeEngagementSurfacesEachUuidUnderItsOwnPreference() {
        ECPublicKey key = (ECPublicKey) CoseKeys.generateEphemeral().getPublic();
        Map<BleMode, UUID> uuids = new LinkedHashMap<>();
        uuids.put(BleMode.CENTRAL_CLIENT, CENTRAL);
        uuids.put(BleMode.PERIPHERAL_SERVER, PERIPHERAL);
        byte[] encoded = DeviceEngagement.forQr(key, uuids).encode();

        DeviceEngagement asCentral =
                DeviceEngagement.parse(encoded, ModePreference.CENTRAL_CLIENT_FIRST);
        DeviceEngagement asPeripheral =
                DeviceEngagement.parse(encoded, ModePreference.PERIPHERAL_SERVER_FIRST);

        assertEquals(BleMode.CENTRAL_CLIENT, asCentral.mode());
        assertEquals(CENTRAL, asCentral.serviceUuid());
        assertEquals(BleMode.PERIPHERAL_SERVER, asPeripheral.mode());
        assertEquals(PERIPHERAL, asPeripheral.serviceUuid());
    }

    @Test
    public void bothModeEngagementCarriesOneBleMethodNotTwo() {
        ECPublicKey key = (ECPublicKey) CoseKeys.generateEphemeral().getPublic();
        Map<BleMode, UUID> uuids = new LinkedHashMap<>();
        uuids.put(BleMode.CENTRAL_CLIENT, CENTRAL);
        uuids.put(BleMode.PERIPHERAL_SERVER, PERIPHERAL);

        CBORObject methods = CBORObject.DecodeFromBytes(
                DeviceEngagement.forQr(key, uuids).encode()).get(CBORObject.FromObject(2));

        assertEquals(1, methods.size());
    }

    private static byte[] bleAndWifi(ECPublicKey key) {
        List<DeviceRetrievalMethod> methods = Arrays.asList(
                DeviceRetrievalMethod.blePeripheralServerMode(PERIPHERAL),
                DeviceRetrievalMethod.wifiAware(WifiOptions.of(new byte[]{0x14})));
        return DeviceEngagement.forQr(key, methods).encode();
    }

    @Test
    public void aMultiMethodEngagementEmitsEveryMethod() {
        ECPublicKey key = (ECPublicKey) CoseKeys.generateEphemeral().getPublic();

        CBORObject methods = CBORObject.DecodeFromBytes(bleAndWifi(key))
                .get(CBORObject.FromObject(2));

        assertEquals(2, methods.size());
        assertEquals(2, methods.get(0).get(0).AsInt32Value());
        assertEquals(3, methods.get(1).get(0).AsInt32Value());
    }

    @Test
    public void transportPreferenceOrderPicksWifiAwareFirst() {
        ECPublicKey key = (ECPublicKey) CoseKeys.generateEphemeral().getPublic();

        DeviceEngagement parsed = DeviceEngagement.parse(bleAndWifi(key), TransportPreference.of(
                TransportMode.WIFI_AWARE, TransportMode.BLE_PERIPHERAL_SERVER));

        assertEquals(TransportMode.WIFI_AWARE, parsed.retrievalMethod().transportMode());
        assertArrayEquals(new byte[]{0x14}, parsed.retrievalMethod().wifiOptions().supportedBands());
        assertThrows(ProximityException.class, parsed::mode);
        assertThrows(ProximityException.class, parsed::serviceUuid);
    }

    @Test
    public void transportPreferenceOrderPicksBleFirst() {
        ECPublicKey key = (ECPublicKey) CoseKeys.generateEphemeral().getPublic();

        DeviceEngagement parsed = DeviceEngagement.parse(bleAndWifi(key), TransportPreference.of(
                TransportMode.BLE_PERIPHERAL_SERVER, TransportMode.WIFI_AWARE));

        assertEquals(BleMode.PERIPHERAL_SERVER, parsed.mode());
        assertEquals(PERIPHERAL, parsed.serviceUuid());
    }

    @Test
    public void aModePreferenceNeverSelectsWifiAware() {
        ECPublicKey key = (ECPublicKey) CoseKeys.generateEphemeral().getPublic();

        DeviceEngagement parsed = DeviceEngagement.parse(
                bleAndWifi(key), ModePreference.CENTRAL_CLIENT_FIRST);

        assertEquals(BleMode.PERIPHERAL_SERVER, parsed.mode());
    }

    @Test
    public void aWifiOnlyEngagementWithABleOnlyPreferenceIsUnsupported() {
        ECPublicKey key = (ECPublicKey) CoseKeys.generateEphemeral().getPublic();
        byte[] encoded = DeviceEngagement.forQr(key, Collections.singletonList(
                DeviceRetrievalMethod.wifiAware(WifiOptions.of(new byte[]{0x04})))).encode();

        assertThrows(UnsupportedTransportException.class,
                () -> DeviceEngagement.parse(encoded, ModePreference.CENTRAL_CLIENT_FIRST));
    }

    @Test
    public void aParsedEngagementCarriesExactlyTheSelectedMethod() {
        ECPublicKey key = (ECPublicKey) CoseKeys.generateEphemeral().getPublic();

        DeviceEngagement parsed = DeviceEngagement.parse(bleAndWifi(key),
                TransportPreference.of(TransportMode.WIFI_AWARE));

        assertEquals(1, parsed.retrievalMethods().size());
        assertThrows(ProximityException.class,
                () -> DeviceEngagement.forQr(key, Arrays.asList(
                        DeviceRetrievalMethod.blePeripheralServerMode(PERIPHERAL),
                        DeviceRetrievalMethod.wifiAware(WifiOptions.of(new byte[]{0x14}))))
                        .retrievalMethod());
    }
}
