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
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.upokecenter.cbor.CBORObject;

import org.junit.Test;
import org.omnione.did.proximity.sdk.core.ProximityException;
import org.omnione.did.proximity.sdk.core.cbor.CborTags;
import org.omnione.did.proximity.sdk.core.cbor.CoseKeys;
import org.omnione.did.proximity.sdk.core.session.IsoAnnexD;

import java.nio.ByteBuffer;
import java.security.KeyPair;
import java.security.interfaces.ECPublicKey;
import java.util.Collections;
import java.util.UUID;

public class DeviceEngagementTest {

    private static final UUID SERVICE = UUID.fromString("45f2a4d6-1e07-4f2b-9a3c-0d1c2b3a4e5f");

    @Test
    public void encodesVersionAndSecurity() {
        ECPublicKey key = (ECPublicKey) CoseKeys.generateEphemeral().getPublic();

        CBORObject engagement = CBORObject.DecodeFromBytes(
                DeviceEngagement.forQr(key, SERVICE).encode());

        assertEquals("1.0", engagement.get(CBORObject.FromObject(0)).AsString());
        CBORObject security = engagement.get(CBORObject.FromObject(1));
        assertEquals(1, security.get(0).AsInt32Value());
        assertEquals(2, security.size());
    }

    @Test
    public void eDeviceKeyIsTaggedCoseKey() {
        ECPublicKey key = (ECPublicKey) CoseKeys.generateEphemeral().getPublic();

        CBORObject engagement = CBORObject.DecodeFromBytes(
                DeviceEngagement.forQr(key, SERVICE).encode());
        CBORObject eDeviceKeyBytes = engagement.get(CBORObject.FromObject(1)).get(1);

        assertEquals(CborTags.ENCODED_CBOR, eDeviceKeyBytes.getMostOuterTag().ToInt32Checked());
        ECPublicKey restored = CoseKeys.toPublicKey(
                CBORObject.DecodeFromBytes(CborTags.untag(eDeviceKeyBytes)));
        assertEquals(key.getW().getAffineX(), restored.getW().getAffineX());
    }

    @Test
    public void advertisesOnlyPeripheralServerMode() {
        ECPublicKey key = (ECPublicKey) CoseKeys.generateEphemeral().getPublic();

        CBORObject engagement = CBORObject.DecodeFromBytes(
                DeviceEngagement.forQr(key, SERVICE).encode());
        CBORObject methods = engagement.get(CBORObject.FromObject(2));

        assertEquals(1, methods.size());
        CBORObject method = methods.get(0);
        assertEquals(2, method.get(0).AsInt32Value());
        assertEquals(1, method.get(1).AsInt32Value());
        CBORObject options = method.get(2);
        assertTrue(options.get(CBORObject.FromObject(0)).AsBoolean());
        assertFalse(options.get(CBORObject.FromObject(1)).AsBoolean());
        assertNotNull(options.get(CBORObject.FromObject(10)));
        assertNull(options.get(CBORObject.FromObject(11)));
    }

    @Test
    public void serviceUuidIsCarriedAsSixteenBytes() {
        ECPublicKey key = (ECPublicKey) CoseKeys.generateEphemeral().getPublic();

        CBORObject engagement = CBORObject.DecodeFromBytes(
                DeviceEngagement.forQr(key, SERVICE).encode());
        byte[] uuidBytes = engagement.get(CBORObject.FromObject(2)).get(0).get(2)
                .get(CBORObject.FromObject(10)).GetByteString();

        assertEquals(16, uuidBytes.length);
    }

    @Test
    public void qrStringRoundTrips() {
        byte[] engagement = DeviceEngagement
                .forQr((ECPublicKey) CoseKeys.generateEphemeral().getPublic(), SERVICE)
                .encode();

        String qr = EngagementCodec.encode(engagement);

        assertTrue(qr.startsWith("mdoc:"));
        assertFalse(qr.contains("="));
        assertArrayEquals(engagement, EngagementCodec.decode(qr));
    }

    @Test
    public void parseRecoversTheServiceUuidAndKey() {
        KeyPair pair = CoseKeys.generateEphemeral();
        UUID serviceUuid = UUID.randomUUID();
        byte[] encoded = DeviceEngagement
                .forQr((ECPublicKey) pair.getPublic(), serviceUuid).encode();

        DeviceEngagement parsed = DeviceEngagement.parse(encoded);

        assertEquals(serviceUuid, parsed.serviceUuid());
        assertArrayEquals(
                CoseKeys.toCoseKey((ECPublicKey) pair.getPublic()).EncodeToBytes(),
                CoseKeys.toCoseKey(parsed.eDeviceKey()).EncodeToBytes());
    }

    @Test
    public void parseRoundTripsToTheSameBytes() {
        KeyPair pair = CoseKeys.generateEphemeral();
        byte[] encoded = DeviceEngagement
                .forQr((ECPublicKey) pair.getPublic(), UUID.randomUUID()).encode();

        assertArrayEquals(encoded, DeviceEngagement.parse(encoded).encode());
    }

    @Test
    public void parseRejectsAMissingPeripheralServerUuid() {
        CBORObject options = CBORObject.NewMap();
        options.Add(0, true);
        options.Add(1, false);
        CBORObject method = CBORObject.NewArray();
        method.Add(2);
        method.Add(1);
        method.Add(options);
        CBORObject methods = CBORObject.NewArray();
        methods.Add(method);

        KeyPair pair = CoseKeys.generateEphemeral();
        CBORObject security = CBORObject.NewArray();
        security.Add(1);
        security.Add(CborTags.tagged(
                CoseKeys.toCoseKey((ECPublicKey) pair.getPublic()).EncodeToBytes()));

        CBORObject engagement = CBORObject.NewMap();
        engagement.Add(0, "1.0");
        engagement.Add(1, security);
        engagement.Add(2, methods);

        assertThrows(ProximityException.class,
                () -> DeviceEngagement.parse(engagement.EncodeToBytes()));
    }

    @Test
    public void parseRejectsGarbage() {
        assertThrows(ProximityException.class,
                () -> DeviceEngagement.parse(new byte[]{0x00, 0x01, 0x02}));
    }

    @Test
    public void parseSelectsAPeripheralServerMethodListedAfterAnother() {
        UUID usable = UUID.randomUUID();
        CBORObject methods = CBORObject.NewArray();
        methods.Add(bleCentralClientMode());
        methods.Add(blePeripheralServerMode(usable));

        assertEquals(usable, DeviceEngagement.parse(engagement(security(1), methods),
                ModePreference.PERIPHERAL_SERVER_FIRST).serviceUuid());
    }

    @Test
    public void parseSkipsANonBleMethodBeforeAUsableOne() {
        UUID usable = UUID.randomUUID();
        CBORObject methods = CBORObject.NewArray();
        methods.Add(nfcMethod());
        methods.Add(blePeripheralServerMode(usable));

        assertEquals(usable, DeviceEngagement.parse(engagement(security(1), methods)).serviceUuid());
    }

    @Test
    public void parseSelectsACentralClientMethodWhenNoPeripheralServerIsOffered() {
        CBORObject methods = CBORObject.NewArray();
        methods.Add(nfcMethod());
        methods.Add(bleCentralClientMode());

        DeviceEngagement parsed = DeviceEngagement.parse(engagement(security(1), methods));

        assertEquals(BleMode.CENTRAL_CLIENT, parsed.mode());
        assertEquals(new UUID(0L, 0L), parsed.serviceUuid());
    }

    @Test
    public void parseSelectsNfcWhenItLeadsThePreference() {
        CBORObject methods = CBORObject.NewArray();
        methods.Add(nfcMethod());
        methods.Add(blePeripheralServerMode(UUID.randomUUID()));

        DeviceEngagement parsed = DeviceEngagement.parse(engagement(security(1), methods),
                TransportPreference.of(TransportMode.NFC, TransportMode.BLE_PERIPHERAL_SERVER));

        assertTrue(parsed.retrievalMethod().isNfc());
        assertEquals(NfcOptions.of(255, 256), parsed.retrievalMethod().nfcOptions());
    }

    @Test
    public void parseRejectsAListWithNoBleGattMethod() {
        CBORObject methods = CBORObject.NewArray();
        methods.Add(nfcMethod());

        assertThrows(UnsupportedTransportException.class,
                () -> DeviceEngagement.parse(engagement(security(1), methods)));
    }

    @Test
    public void parseRejectsAnUnsupportedCipherSuite() {
        CBORObject methods = CBORObject.NewArray();
        methods.Add(blePeripheralServerMode(SERVICE));

        ProximityException thrown = assertThrows(ProximityException.class,
                () -> DeviceEngagement.parse(engagement(security(2), methods)));

        assertTrue(thrown.getMessage(), thrown.getMessage().contains("cipher suite"));
    }

    private static CBORObject security(int cipherSuite) {
        CBORObject security = CBORObject.NewArray();
        security.Add(cipherSuite);
        security.Add(CborTags.tagged(CoseKeys
                .toCoseKey((ECPublicKey) CoseKeys.generateEphemeral().getPublic())
                .EncodeToBytes()));
        return security;
    }

    private static byte[] engagement(CBORObject security, CBORObject methods) {
        CBORObject engagement = CBORObject.NewMap();
        engagement.Add(0, "1.0");
        engagement.Add(1, security);
        engagement.Add(2, methods);
        return engagement.EncodeToBytes();
    }

    private static CBORObject blePeripheralServerMode(UUID serviceUuid) {
        ByteBuffer buffer = ByteBuffer.allocate(16);
        buffer.putLong(serviceUuid.getMostSignificantBits());
        buffer.putLong(serviceUuid.getLeastSignificantBits());
        CBORObject options = CBORObject.NewMap();
        options.Add(0, true);
        options.Add(1, false);
        options.Add(10, buffer.array());
        return bleMethod(options);
    }

    private static CBORObject bleCentralClientMode() {
        CBORObject options = CBORObject.NewMap();
        options.Add(0, false);
        options.Add(1, true);
        options.Add(11, new byte[16]);
        return bleMethod(options);
    }

    @Test
    public void qrEncodingStillMatchesTheIsoAnnexD31Vector() {
        ECPublicKey eDeviceKey = IsoAnnexD.publicKey(
                IsoAnnexD.EPHEMERAL_DEVICE_X, IsoAnnexD.EPHEMERAL_DEVICE_Y);
        UUID serviceUuid = UUID.fromString("45efef74-2b2c-4837-a9a3-b0e1d05a6917");

        byte[] encoded = DeviceEngagement
                .forQr(eDeviceKey, Collections.singletonMap(BleMode.CENTRAL_CLIENT, serviceUuid))
                .encode();

        assertArrayEquals("omitting key 2 for NFC must never leak into the QR encoding",
                IsoAnnexD.hex(IsoAnnexD.QR_DEVICE_ENGAGEMENT_D_3_1), encoded);
    }

    @Test
    public void nfcEngagementOmitsTheDeviceRetrievalMethods() {
        ECPublicKey eDeviceKey = IsoAnnexD.publicKey(
                IsoAnnexD.EPHEMERAL_DEVICE_X, IsoAnnexD.EPHEMERAL_DEVICE_Y);

        byte[] encoded = DeviceEngagement.forNfc(eDeviceKey).encode();
        CBORObject engagement = CBORObject.DecodeFromBytes(encoded);

        assertEquals(0xA2, encoded[0] & 0xFF);
        assertEquals(2, engagement.size());
        assertNotNull(engagement.get(0));
        assertNotNull(engagement.get(1));
        assertNull(engagement.get(2));
    }

    @Test
    public void nfcEngagementIsByteIdenticalForStaticAndNegotiatedHandover() {
        ECPublicKey eDeviceKey = IsoAnnexD.publicKey(
                IsoAnnexD.EPHEMERAL_DEVICE_X, IsoAnnexD.EPHEMERAL_DEVICE_Y);

        assertArrayEquals(DeviceEngagement.forNfc(eDeviceKey).encode(),
                DeviceEngagement.forNfc(eDeviceKey).encode());
    }

    @Test
    public void parseNfcAcceptsAnEngagementWithoutRetrievalMethodsAndParseRejectsIt() {
        ECPublicKey eDeviceKey = IsoAnnexD.publicKey(
                IsoAnnexD.EPHEMERAL_DEVICE_X, IsoAnnexD.EPHEMERAL_DEVICE_Y);
        byte[] encoded = DeviceEngagement.forNfc(eDeviceKey).encode();

        DeviceEngagement parsed = DeviceEngagement.parseNfc(encoded);

        assertFalse(parsed.hasRetrievalMethods());
        assertArrayEquals(eDeviceKey.getEncoded(), parsed.eDeviceKey().getEncoded());
        assertThrows(ProximityException.class, () -> DeviceEngagement.parse(encoded));
    }

    @Test
    public void nfcEngagementKeepsTheSameEDeviceKeyBytesAsTheQrEncoding() {
        ECPublicKey eDeviceKey = IsoAnnexD.publicKey(
                IsoAnnexD.EPHEMERAL_DEVICE_X, IsoAnnexD.EPHEMERAL_DEVICE_Y);
        UUID serviceUuid = UUID.fromString("45efef74-2b2c-4837-a9a3-b0e1d05a6917");

        assertArrayEquals(
                DeviceEngagement.forQr(eDeviceKey,
                        Collections.singletonMap(BleMode.CENTRAL_CLIENT, serviceUuid))
                        .eDeviceKeyBytes(),
                DeviceEngagement.forNfc(eDeviceKey).eDeviceKeyBytes());
    }

    @Test
    public void carrierAccessorsFailLoudlyOnAnNfcEngagement() {
        DeviceEngagement engagement = DeviceEngagement.forNfc(IsoAnnexD.publicKey(
                IsoAnnexD.EPHEMERAL_DEVICE_X, IsoAnnexD.EPHEMERAL_DEVICE_Y));

        assertThrows(ProximityException.class, engagement::serviceUuid);
        assertThrows(ProximityException.class, engagement::mode);
    }

    private static CBORObject bleMethod(CBORObject options) {
        CBORObject method = CBORObject.NewArray();
        method.Add(2);
        method.Add(1);
        method.Add(options);
        return method;
    }

    private static CBORObject nfcMethod() {
        CBORObject options = CBORObject.NewMap();
        options.Add(0, 255);
        options.Add(1, 256);
        CBORObject method = CBORObject.NewArray();
        method.Add(1);
        method.Add(1);
        method.Add(options);
        return method;
    }
}
