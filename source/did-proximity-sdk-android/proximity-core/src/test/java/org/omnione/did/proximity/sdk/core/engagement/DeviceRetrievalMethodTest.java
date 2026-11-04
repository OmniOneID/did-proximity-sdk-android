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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.upokecenter.cbor.CBORObject;

import org.junit.Test;
import org.omnione.did.proximity.sdk.core.ProximityException;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class DeviceRetrievalMethodTest {

    @Test
    public void parsesTheMultipazCentralClientEngagement() {
        CBORObject method = CBORObject.DecodeFromBytes(run14()).get(CBORObject.FromObject(2)).get(0);

        List<DeviceRetrievalMethod> candidates = DeviceRetrievalMethod.parse(method);

        assertEquals(1, candidates.size());
        assertEquals(BleMode.CENTRAL_CLIENT, candidates.get(0).mode());
        assertEquals(UUID.fromString("700f27db-b8bd-c72b-a2fe-526d751f9a56"),
                candidates.get(0).serviceUuid());
    }

    @Test
    public void bleCentralClientModeMatchesTheCapturedMultipazOptionsByteForByte() {
        CBORObject capturedOptions = CBORObject.DecodeFromBytes(run14())
                .get(CBORObject.FromObject(2)).get(0).get(2);

        UUID uuid = UUID.fromString("700f27db-b8bd-c72b-a2fe-526d751f9a56");
        CBORObject ourOptions = DeviceRetrievalMethod.bleCentralClientMode(uuid).toCbor().get(2);

        assertArrayEquals(capturedOptions.EncodeToBytes(), ourOptions.EncodeToBytes());
    }

    @Test
    public void parsesPeripheralServerModeFromKeyTen() {
        List<DeviceRetrievalMethod> candidates = DeviceRetrievalMethod.parse(
                method(true, false, uuidBytes(), null));

        assertEquals(1, candidates.size());
        assertEquals(BleMode.PERIPHERAL_SERVER, candidates.get(0).mode());
    }

    @Test
    public void bothModesYieldTwoCandidatesCentralClientFirst() {
        List<DeviceRetrievalMethod> candidates = DeviceRetrievalMethod.parse(
                method(true, true, uuidBytes(), uuidBytes()));

        assertEquals(2, candidates.size());
        assertEquals(BleMode.CENTRAL_CLIENT, candidates.get(0).mode());
        assertEquals(BleMode.PERIPHERAL_SERVER, candidates.get(1).mode());
    }

    @Test
    public void rejectsWhenNeitherModeIsAdvertised() {
        assertThrows(ProximityException.class,
                () -> DeviceRetrievalMethod.parse(method(false, false, uuidBytes(), uuidBytes())));
    }

    @Test
    public void rejectsAModeAdvertisedWithoutItsUuid() {
        assertThrows(ProximityException.class,
                () -> DeviceRetrievalMethod.parse(method(false, true, uuidBytes(), null)));
    }

    @Test
    public void rejectsAnUnknownRetrievalMethodType() {
        CBORObject method = CBORObject.NewArray();
        method.Add(9);
        method.Add(1);
        method.Add(CBORObject.NewMap());

        assertThrows(ProximityException.class, () -> DeviceRetrievalMethod.parse(method));
    }

    @Test
    public void rejectsAnNfcMethodWithoutItsLimits() {
        CBORObject method = CBORObject.NewArray();
        method.Add(1);
        method.Add(1);
        method.Add(CBORObject.NewMap());

        assertThrows(ProximityException.class, () -> DeviceRetrievalMethod.parse(method));
    }

    @Test
    public void parsesAnNfcRetrievalMethod() {
        CBORObject options = CBORObject.NewMap();
        options.Add(0, 65535);
        options.Add(1, 65536);
        CBORObject method = CBORObject.NewArray();
        method.Add(1);
        method.Add(1);
        method.Add(options);

        List<DeviceRetrievalMethod> candidates = DeviceRetrievalMethod.parse(method);

        assertEquals(1, candidates.size());
        DeviceRetrievalMethod nfc = candidates.get(0);
        assertTrue(nfc.isNfc());
        assertFalse(nfc.isWifiAware());
        assertEquals(TransportMode.NFC, nfc.transportMode());
        assertEquals(NfcOptions.of(65535, 65536), nfc.nfcOptions());
        assertThrows(ProximityException.class, nfc::mode);
        assertThrows(ProximityException.class, nfc::serviceUuid);
        assertThrows(ProximityException.class, nfc::wifiOptions);
        assertTrue(nfc.modes().isEmpty());
        assertNull(nfc.psm());
    }

    @Test
    public void nfcRoundTripsAsTypeOneVersionOne() {
        DeviceRetrievalMethod nfc = DeviceRetrievalMethod.nfc(NfcOptions.of(255, 256));

        CBORObject cbor = nfc.toCbor();

        assertEquals(1, cbor.get(0).AsInt32Value());
        assertEquals(1, cbor.get(1).AsInt32Value());
        assertEquals(255, cbor.get(2).get(0).AsInt32Value());
        assertEquals(256, cbor.get(2).get(1).AsInt32Value());
        DeviceRetrievalMethod parsed = DeviceRetrievalMethod.parse(
                CBORObject.DecodeFromBytes(cbor.EncodeToBytes())).get(0);
        assertEquals(nfc.nfcOptions(), parsed.nfcOptions());
        assertSame(nfc, nfc.withPsm(null));
        assertThrows(ProximityException.class, () -> nfc.withPsm(1));
    }

    @Test
    public void parsesAWifiAwareRetrievalMethod() {
        CBORObject options = CBORObject.NewMap();
        options.Add(3, new byte[]{0x14});
        CBORObject method = CBORObject.NewArray();
        method.Add(3);
        method.Add(1);
        method.Add(options);

        List<DeviceRetrievalMethod> candidates = DeviceRetrievalMethod.parse(method);

        assertEquals(1, candidates.size());
        DeviceRetrievalMethod wifi = candidates.get(0);
        assertTrue(wifi.isWifiAware());
        assertEquals(TransportMode.WIFI_AWARE, wifi.transportMode());
        assertArrayEquals(new byte[]{0x14}, wifi.wifiOptions().supportedBands());
        assertThrows(ProximityException.class, wifi::mode);
        assertThrows(ProximityException.class, wifi::serviceUuid);
        assertTrue(wifi.modes().isEmpty());
    }

    @Test
    public void wifiAwareEncodesAsTypeThreeVersionOne() {
        DeviceRetrievalMethod wifi = DeviceRetrievalMethod.wifiAware(
                WifiOptions.of(new byte[]{0x04}));

        CBORObject cbor = wifi.toCbor();

        assertEquals(3, cbor.get(0).AsInt32Value());
        assertEquals(1, cbor.get(1).AsInt32Value());
        assertArrayEquals(new byte[]{0x04}, cbor.get(2).get(3).GetByteString());
        assertEquals(wifi.wifiOptions(), DeviceRetrievalMethod.parse(cbor).get(0).wifiOptions());
    }

    @Test
    public void aBleMethodIsNotWifiAware() {
        DeviceRetrievalMethod ble = DeviceRetrievalMethod.blePeripheralServerMode(UUID.randomUUID());

        assertFalse(ble.isWifiAware());
        assertEquals(TransportMode.BLE_PERIPHERAL_SERVER, ble.transportMode());
        assertThrows(ProximityException.class, ble::wifiOptions);
    }

    @Test
    public void rejectsAUuidThatIsNotSixteenBytes() {
        assertThrows(ProximityException.class,
                () -> DeviceRetrievalMethod.parse(method(true, false, new byte[15], null)));
    }

    private static CBORObject method(boolean peripheralServer, boolean centralClient,
                                     byte[] peripheralUuid, byte[] centralUuid) {
        CBORObject options = CBORObject.NewMap();
        options.Add(0, peripheralServer);
        options.Add(1, centralClient);
        if (peripheralUuid != null) {
            options.Add(10, peripheralUuid);
        }
        if (centralUuid != null) {
            options.Add(11, centralUuid);
        }
        CBORObject method = CBORObject.NewArray();
        method.Add(2);
        method.Add(1);
        method.Add(options);
        return method;
    }

    @Test
    public void peripheralServerModeEncodesKeyZeroAndKeyTen() {
        UUID uuid = UUID.fromString("11111111-2222-3333-4444-555555555555");

        CBORObject options = DeviceRetrievalMethod.blePeripheralServerMode(uuid).toCbor().get(2);

        assertTrue(options.get(0).AsBoolean());
        assertFalse(options.get(1).AsBoolean());
        assertArrayEquals(bytesOf(uuid), options.get(10).GetByteString());
        assertNull(options.get(11));
    }

    @Test
    public void centralClientModeEncodesKeyOneAndKeyEleven() {
        UUID uuid = UUID.fromString("66666666-7777-8888-9999-aaaaaaaaaaaa");

        CBORObject options = DeviceRetrievalMethod.bleCentralClientMode(uuid).toCbor().get(2);

        assertFalse(options.get(0).AsBoolean());
        assertTrue(options.get(1).AsBoolean());
        assertArrayEquals(bytesOf(uuid), options.get(11).GetByteString());
        assertNull(options.get(10));
    }

    @Test
    public void centralClientModeRoundTripsThroughParse() {
        UUID uuid = UUID.fromString("66666666-7777-8888-9999-aaaaaaaaaaaa");

        List<DeviceRetrievalMethod> parsed = DeviceRetrievalMethod.parse(
                DeviceRetrievalMethod.bleCentralClientMode(uuid).toCbor());

        assertEquals(1, parsed.size());
        assertEquals(BleMode.CENTRAL_CLIENT, parsed.get(0).mode());
        assertEquals(uuid, parsed.get(0).serviceUuid());
    }

    private static byte[] bytesOf(UUID uuid) {
        ByteBuffer buffer = ByteBuffer.allocate(16);
        buffer.putLong(uuid.getMostSignificantBits());
        buffer.putLong(uuid.getLeastSignificantBits());
        return buffer.array();
    }

    private static byte[] uuidBytes() {
        return new byte[16];
    }

    private static byte[] run14() {
        try (InputStream stream = DeviceRetrievalMethodTest.class
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

    private static Map<BleMode, UUID> bothUuids(UUID central, UUID peripheral) {
        Map<BleMode, UUID> uuids = new LinkedHashMap<>();
        uuids.put(BleMode.CENTRAL_CLIENT, central);
        uuids.put(BleMode.PERIPHERAL_SERVER, peripheral);
        return uuids;
    }

    @Test
    public void bothModeEncodesBothFlagsAndBothUuids() {
        UUID central = UUID.fromString("66666666-7777-8888-9999-aaaaaaaaaaaa");
        UUID peripheral = UUID.fromString("11111111-2222-3333-4444-555555555555");

        CBORObject options = DeviceRetrievalMethod.ble(bothUuids(central, peripheral))
                .toCbor().get(2);

        assertTrue(options.get(0).AsBoolean());
        assertTrue(options.get(1).AsBoolean());
        assertEquals(16, options.get(10).GetByteString().length);
        assertEquals(16, options.get(11).GetByteString().length);
    }

    @Test
    public void centralOnlyOmitsThePeripheralUuid() {
        UUID central = UUID.fromString("66666666-7777-8888-9999-aaaaaaaaaaaa");

        CBORObject options = DeviceRetrievalMethod.bleCentralClientMode(central).toCbor().get(2);

        assertTrue(options.get(1).AsBoolean());
        assertEquals(false, options.get(0).AsBoolean());
        assertNull(options.get(10));
        assertNotNull(options.get(11));
    }

    @Test
    public void peripheralOnlyOmitsTheCentralUuid() {
        UUID peripheral = UUID.fromString("11111111-2222-3333-4444-555555555555");

        CBORObject options = DeviceRetrievalMethod.blePeripheralServerMode(peripheral)
                .toCbor().get(2);

        assertTrue(options.get(0).AsBoolean());
        assertEquals(false, options.get(1).AsBoolean());
        assertNotNull(options.get(10));
        assertNull(options.get(11));
    }

    @Test
    public void bothModeRoundTripsIntoTwoSingleModeCandidates() {
        UUID central = UUID.fromString("66666666-7777-8888-9999-aaaaaaaaaaaa");
        UUID peripheral = UUID.fromString("11111111-2222-3333-4444-555555555555");

        List<DeviceRetrievalMethod> parsed = DeviceRetrievalMethod.parse(
                DeviceRetrievalMethod.ble(bothUuids(central, peripheral)).toCbor());

        assertEquals(2, parsed.size());
        assertEquals(BleMode.CENTRAL_CLIENT, parsed.get(0).mode());
        assertEquals(central, parsed.get(0).serviceUuid());
        assertEquals(BleMode.PERIPHERAL_SERVER, parsed.get(1).mode());
        assertEquals(peripheral, parsed.get(1).serviceUuid());
    }

    @Test
    public void anEmptyModeMapIsRejected() {
        assertThrows(ProximityException.class,
                () -> DeviceRetrievalMethod.ble(new LinkedHashMap<>()));
    }

    @Test
    public void modeIsRejectedOnABothModeMethod() {
        UUID central = UUID.fromString("66666666-7777-8888-9999-aaaaaaaaaaaa");
        UUID peripheral = UUID.fromString("11111111-2222-3333-4444-555555555555");

        assertThrows(ProximityException.class,
                () -> DeviceRetrievalMethod.ble(bothUuids(central, peripheral)).mode());
    }

    private static CBORObject bleMethod(boolean peripheral, boolean central) {
        CBORObject options = CBORObject.NewMap();
        options.Add(0, peripheral);
        options.Add(1, central);
        if (peripheral) {
            options.Add(10, new byte[16]);
        }
        if (central) {
            options.Add(11, new byte[16]);
        }
        CBORObject method = CBORObject.NewArray();
        method.Add(2);
        method.Add(1);
        method.Add(options);
        return method;
    }

    @Test
    public void aPeripheralMethodCarryingMacAndPsmIsAcceptedAndKept() {
        CBORObject method = bleMethod(true, false);
        method.get(2).Add(20, new byte[]{1, 2, 3, 4, 5, 6});
        method.get(2).Add(21, 192);

        List<DeviceRetrievalMethod> parsed = DeviceRetrievalMethod.parse(method);

        assertEquals(1, parsed.size());
        assertEquals(BleMode.PERIPHERAL_SERVER, parsed.get(0).mode());
        assertEquals(Integer.valueOf(192), parsed.get(0).psm());
    }

    @Test
    public void aBothModeMethodCarryingMacAndPsmIsAccepted() {
        CBORObject method = bleMethod(true, true);
        method.get(2).Add(21, 192);

        List<DeviceRetrievalMethod> parsed = DeviceRetrievalMethod.parse(method);

        assertEquals(2, parsed.size());
        assertEquals(BleMode.CENTRAL_CLIENT, parsed.get(0).mode());
        assertNull(parsed.get(0).psm());
        assertEquals(BleMode.PERIPHERAL_SERVER, parsed.get(1).mode());
        assertEquals(Integer.valueOf(192), parsed.get(1).psm());
    }

    @Test
    public void aPeripheralMethodWithoutAPsmParsesToNull() {
        List<DeviceRetrievalMethod> parsed = DeviceRetrievalMethod.parse(bleMethod(true, false));

        assertNull(parsed.get(0).psm());
    }

    @Test
    public void withPsmOnACentralOnlyMethodIsRejected() {
        UUID uuid = UUID.fromString("66666666-7777-8888-9999-aaaaaaaaaaaa");

        assertThrows(ProximityException.class,
                () -> DeviceRetrievalMethod.bleCentralClientMode(uuid).withPsm(192));
    }

    @Test
    public void withPsmOutOfRangeIsRejected() {
        UUID uuid = UUID.fromString("11111111-2222-3333-4444-555555555555");

        assertThrows(ProximityException.class,
                () -> DeviceRetrievalMethod.blePeripheralServerMode(uuid).withPsm(0));
        assertThrows(ProximityException.class,
                () -> DeviceRetrievalMethod.blePeripheralServerMode(uuid).withPsm(0x10000));
    }

    @Test
    public void aParsedPsmOutOfRangeIsRejected() {
        CBORObject method = bleMethod(true, false);
        method.get(2).Add(21, 0x10000);

        assertThrows(ProximityException.class, () -> DeviceRetrievalMethod.parse(method));
    }

    @Test
    public void thePsmRoundTripsThroughKey21() {
        UUID uuid = UUID.fromString("11111111-2222-3333-4444-555555555555");
        DeviceRetrievalMethod method =
                DeviceRetrievalMethod.blePeripheralServerMode(uuid).withPsm(192);

        CBORObject options = method.toCbor().get(2);
        assertEquals(192, options.get(21).AsInt32Value());

        List<DeviceRetrievalMethod> parsed = DeviceRetrievalMethod.parse(method.toCbor());
        assertEquals(Integer.valueOf(192), parsed.get(0).psm());
        assertEquals(uuid, parsed.get(0).serviceUuid());
    }

    @Test
    public void key21IsAbsentWithoutAPsm() {
        UUID uuid = UUID.fromString("11111111-2222-3333-4444-555555555555");

        CBORObject options = DeviceRetrievalMethod.blePeripheralServerMode(uuid).toCbor().get(2);

        assertNull(options.get(21));
        assertNull(DeviceRetrievalMethod.blePeripheralServerMode(uuid).withPsm(null).psm());
    }

    @Test
    public void aCentralOnlyMethodCarryingAPsmIsRejected() {
        CBORObject method = bleMethod(false, true);
        method.get(2).Add(21, 192);

        assertThrows(ProximityException.class, () -> DeviceRetrievalMethod.parse(method));
    }

    @Test
    public void aCentralOnlyMethodCarryingAMacAddressIsRejected() {
        CBORObject method = bleMethod(false, true);
        method.get(2).Add(20, new byte[]{1, 2, 3, 4, 5, 6});

        assertThrows(ProximityException.class, () -> DeviceRetrievalMethod.parse(method));
    }

    @Test
    public void aPeripheralMethodWithATextPsmIsRejected() {
        CBORObject method = bleMethod(true, false);
        method.get(2).Add(21, "192");

        assertThrows(ProximityException.class, () -> DeviceRetrievalMethod.parse(method));
    }

    @Test
    public void aPeripheralMethodWithANonByteStringMacAddressIsRejected() {
        CBORObject method = bleMethod(true, false);
        method.get(2).Add(20, 42);

        assertThrows(ProximityException.class, () -> DeviceRetrievalMethod.parse(method));
    }
}
