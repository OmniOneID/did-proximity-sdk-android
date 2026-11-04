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

package org.omnione.did.proximity.sdk.core.message;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.upokecenter.cbor.CBORObject;

import org.junit.Test;
import org.omnione.did.proximity.sdk.core.cbor.CborTags;
import org.omnione.did.proximity.sdk.core.cbor.CoseKeys;
import org.omnione.did.proximity.sdk.core.engagement.SessionTranscripts;

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.util.List;

public class DeviceAuthVerifierTest {

    private static final String DOC_TYPE = "org.iso.18013.5.1.mDL";
    private static final byte[] ES256_PROTECTED = {(byte) 0xA1, 0x01, 0x26};

    private static byte[] transcript() {
        return SessionTranscripts.forQr(new byte[]{(byte) 0xA0}, new byte[]{(byte) 0xA1});
    }

    private static CBORObject nameSpacesBytes() {
        return CborTags.tagged(CBORObject.NewMap().EncodeToBytes());
    }

    @Test
    public void aFreshlySignedResponseVerifies() {
        KeyPair device = CoseKeys.generateEphemeral();
        byte[] response = signedResponse(device, transcript(), nameSpacesBytes());

        List<DeviceAuthResult> results =
                DeviceAuthVerifier.verify(response, transcript(), null);

        assertEquals(1, results.size());
        assertTrue(String.valueOf(results.get(0).detail), results.get(0).verified());
        assertEquals(DeviceAuthResult.Method.SIGNATURE, results.get(0).method);
    }

    @Test
    public void aFlippedSignatureByteFails() {
        KeyPair device = CoseKeys.generateEphemeral();
        CBORObject response =
                CBORObject.DecodeFromBytes(signedResponse(device, transcript(), nameSpacesBytes()));
        CBORObject deviceAuth = response.get("documents").get(0)
                .get("deviceSigned").get("deviceAuth");
        byte[] signature = deviceAuth.get("deviceSignature").get(3).GetByteString();
        signature[0] ^= 0x01;
        CBORObject tampered = CBORObject.NewArray();
        tampered.Add(ES256_PROTECTED);
        tampered.Add(CBORObject.NewMap());
        tampered.Add(CBORObject.Null);
        tampered.Add(signature);
        deviceAuth.Set("deviceSignature", tampered);

        List<DeviceAuthResult> results =
                DeviceAuthVerifier.verify(response.EncodeToBytes(), transcript(), null);

        assertEquals(DeviceAuthResult.Outcome.INVALID, results.get(0).outcome);
        assertEquals(DeviceAuthResult.Method.SIGNATURE, results.get(0).method);
    }

    @Test
    public void aDifferentSessionTranscriptFails() {
        KeyPair device = CoseKeys.generateEphemeral();
        byte[] response = signedResponse(device, transcript(), nameSpacesBytes());
        byte[] other = SessionTranscripts.forQr(
                new byte[]{(byte) 0xA0}, new byte[]{(byte) 0xA2});

        List<DeviceAuthResult> results = DeviceAuthVerifier.verify(response, other, null);

        assertEquals(DeviceAuthResult.Outcome.INVALID, results.get(0).outcome);
    }

    @Test
    public void aSignatureFromAnotherKeyFails() {
        KeyPair device = CoseKeys.generateEphemeral();
        KeyPair impostor = CoseKeys.generateEphemeral();
        CBORObject response =
                CBORObject.DecodeFromBytes(signedResponse(device, transcript(), nameSpacesBytes()));
        CBORObject deviceKey = CoseKeys.toCoseKey((ECPublicKey) impostor.getPublic());
        response.get("documents").get(0).get("issuerSigned").Set("issuerAuth",
                issuerAuth(deviceKey));

        List<DeviceAuthResult> results =
                DeviceAuthVerifier.verify(response.EncodeToBytes(), transcript(), null);

        assertEquals(DeviceAuthResult.Outcome.INVALID, results.get(0).outcome);
    }

    @Test
    public void carryingBothASignatureAndAMacIsRejected() {
        KeyPair device = CoseKeys.generateEphemeral();
        CBORObject response =
                CBORObject.DecodeFromBytes(signedResponse(device, transcript(), nameSpacesBytes()));
        CBORObject mac = CBORObject.NewArray();
        mac.Add(new byte[]{(byte) 0xA1, 0x01, 0x05});
        mac.Add(CBORObject.NewMap());
        mac.Add(CBORObject.Null);
        mac.Add(new byte[32]);
        response.get("documents").get(0).get("deviceSigned").get("deviceAuth")
                .Add("deviceMac", mac);

        List<DeviceAuthResult> results =
                DeviceAuthVerifier.verify(response.EncodeToBytes(), transcript(), null);

        assertEquals(DeviceAuthResult.Outcome.INVALID, results.get(0).outcome);
        assertEquals(DeviceAuthResult.Method.NONE, results.get(0).method);
        assertNotNull(results.get(0).detail);
        assertTrue(results.get(0).detail.contains("both"));
    }

    @Test
    public void carryingNeitherASignatureNorAMacIsRejected() {
        KeyPair device = CoseKeys.generateEphemeral();
        CBORObject response =
                CBORObject.DecodeFromBytes(signedResponse(device, transcript(), nameSpacesBytes()));
        response.get("documents").get(0).get("deviceSigned")
                .Set("deviceAuth", CBORObject.NewMap());

        List<DeviceAuthResult> results =
                DeviceAuthVerifier.verify(response.EncodeToBytes(), transcript(), null);

        assertEquals(DeviceAuthResult.Outcome.ABSENT, results.get(0).outcome);
        assertEquals(DeviceAuthResult.Method.NONE, results.get(0).method);
        assertTrue(results.get(0).detail.contains("neither"));
    }

    @Test
    public void aDocumentWithoutDeviceSignedIsAbsentNotInvalid() {
        KeyPair device = CoseKeys.generateEphemeral();
        CBORObject response =
                CBORObject.DecodeFromBytes(signedResponse(device, transcript(), nameSpacesBytes()));
        response.get("documents").get(0).Remove(CBORObject.FromObject("deviceSigned"));

        List<DeviceAuthResult> results =
                DeviceAuthVerifier.verify(response.EncodeToBytes(), transcript(), null);

        assertEquals(DeviceAuthResult.Outcome.ABSENT, results.get(0).outcome);
        assertEquals(DeviceAuthResult.Method.NONE, results.get(0).method);
    }

    @Test
    public void aDeviceSignedWithoutDeviceAuthIsAbsentNotInvalid() {
        KeyPair device = CoseKeys.generateEphemeral();
        CBORObject response =
                CBORObject.DecodeFromBytes(signedResponse(device, transcript(), nameSpacesBytes()));
        response.get("documents").get(0).get("deviceSigned")
                .Remove(CBORObject.FromObject("deviceAuth"));

        List<DeviceAuthResult> results =
                DeviceAuthVerifier.verify(response.EncodeToBytes(), transcript(), null);

        assertEquals(DeviceAuthResult.Outcome.ABSENT, results.get(0).outcome);
    }

    @Test
    public void aMalformedIssuerAuthIsInvalidNotAbsent() {
        KeyPair device = CoseKeys.generateEphemeral();
        CBORObject response =
                CBORObject.DecodeFromBytes(signedResponse(device, transcript(), nameSpacesBytes()));
        response.get("documents").get(0).get("issuerSigned")
                .Set("issuerAuth", CBORObject.NewMap());

        List<DeviceAuthResult> results =
                DeviceAuthVerifier.verify(response.EncodeToBytes(), transcript(), null);

        assertEquals(DeviceAuthResult.Outcome.INVALID, results.get(0).outcome);
        assertNotNull(results.get(0).detail);
    }

    @Test
    public void aDeviceSignedWithoutNameSpacesIsInvalidNotAbsent() {
        KeyPair device = CoseKeys.generateEphemeral();
        CBORObject response =
                CBORObject.DecodeFromBytes(signedResponse(device, transcript(), nameSpacesBytes()));
        response.get("documents").get(0).get("deviceSigned")
                .Remove(CBORObject.FromObject("nameSpaces"));

        List<DeviceAuthResult> results =
                DeviceAuthVerifier.verify(response.EncodeToBytes(), transcript(), null);

        assertEquals(DeviceAuthResult.Outcome.INVALID, results.get(0).outcome);
    }

    @Test
    public void aResponseWithoutDocumentsYieldsNoResults() {
        CBORObject response = CBORObject.NewMap();
        response.Add("version", "1.0");
        response.Add("status", 0);

        assertTrue(DeviceAuthVerifier.verify(
                response.EncodeToBytes(), transcript(), null).isEmpty());
    }

    @Test
    public void everyDocumentIsVerifiedIndependently() {
        KeyPair device = CoseKeys.generateEphemeral();
        CBORObject good =
                CBORObject.DecodeFromBytes(signedResponse(device, transcript(), nameSpacesBytes()));
        CBORObject bad =
                CBORObject.DecodeFromBytes(signedResponse(device, transcript(), nameSpacesBytes()));
        bad.get("documents").get(0).get("deviceSigned")
                .Set("deviceAuth", CBORObject.NewMap());
        good.get("documents").Add(bad.get("documents").get(0));

        List<DeviceAuthResult> results =
                DeviceAuthVerifier.verify(good.EncodeToBytes(), transcript(), null);

        assertEquals(2, results.size());
        assertEquals(DeviceAuthResult.Outcome.VERIFIED, results.get(0).outcome);
        assertEquals(DeviceAuthResult.Outcome.ABSENT, results.get(1).outcome);
    }

    private static byte[] signedResponse(KeyPair device,
                                         byte[] transcript,
                                         CBORObject nameSpaces) {
        CBORObject authentication = CBORObject.NewArray();
        authentication.Add("DeviceAuthentication");
        authentication.Add(CBORObject.DecodeFromBytes(transcript));
        authentication.Add(DOC_TYPE);
        authentication.Add(nameSpaces);
        byte[] authenticationBytes =
                CborTags.tagged(authentication.EncodeToBytes()).EncodeToBytes();

        CBORObject sigStructure = CBORObject.NewArray();
        sigStructure.Add("Signature1");
        sigStructure.Add(ES256_PROTECTED);
        sigStructure.Add(new byte[0]);
        sigStructure.Add(authenticationBytes);

        CBORObject deviceSignature = CBORObject.NewArray();
        deviceSignature.Add(ES256_PROTECTED);
        deviceSignature.Add(CBORObject.NewMap());
        deviceSignature.Add(CBORObject.Null);
        deviceSignature.Add(rawSignature(device, sigStructure.EncodeToBytes()));

        CBORObject deviceAuth = CBORObject.NewMap();
        deviceAuth.Add("deviceSignature", deviceSignature);

        CBORObject deviceSigned = CBORObject.NewMap();
        deviceSigned.Add("nameSpaces", nameSpaces);
        deviceSigned.Add("deviceAuth", deviceAuth);

        CBORObject document = CBORObject.NewMap();
        document.Add("docType", DOC_TYPE);
        document.Add("issuerSigned",
                issuerSignedWith(CoseKeys.toCoseKey((ECPublicKey) device.getPublic())));
        document.Add("deviceSigned", deviceSigned);

        CBORObject documents = CBORObject.NewArray();
        documents.Add(document);

        CBORObject response = CBORObject.NewMap();
        response.Add("version", "1.0");
        response.Add("documents", documents);
        response.Add("status", 0);
        return response.EncodeToBytes();
    }

    private static CBORObject issuerSignedWith(CBORObject deviceKey) {
        CBORObject issuerSigned = CBORObject.NewMap();
        issuerSigned.Add("nameSpaces", CBORObject.NewMap());
        issuerSigned.Add("issuerAuth", issuerAuth(deviceKey));
        return issuerSigned;
    }

    private static CBORObject issuerAuth(CBORObject deviceKey) {
        CBORObject deviceKeyInfo = CBORObject.NewMap();
        deviceKeyInfo.Add("deviceKey", deviceKey);

        CBORObject mso = CBORObject.NewMap();
        mso.Add("docType", DOC_TYPE);
        mso.Add("deviceKeyInfo", deviceKeyInfo);

        CBORObject issuerAuth = CBORObject.NewArray();
        issuerAuth.Add(new byte[]{(byte) 0xA1, 0x01, 0x26});
        issuerAuth.Add(CBORObject.NewMap());
        issuerAuth.Add(CborTags.tagged(mso.EncodeToBytes()).EncodeToBytes());
        issuerAuth.Add(new byte[64]);
        return issuerAuth;
    }

    static byte[] rawSignature(KeyPair device, byte[] message) {
        try {
            Signature signer = Signature.getInstance("SHA256withECDSA");
            signer.initSign(device.getPrivate());
            signer.update(message);
            return rawFromDer(signer.sign());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] rawFromDer(byte[] der) {
        int index = 3;
        int rLength = der[index] & 0xFF;
        BigInteger r = new BigInteger(1, slice(der, index + 1, rLength));
        index = index + 1 + rLength + 1;
        int sLength = der[index] & 0xFF;
        BigInteger s = new BigInteger(1, slice(der, index + 1, sLength));

        byte[] raw = new byte[64];
        copyInto(raw, 0, r);
        copyInto(raw, 32, s);
        return raw;
    }

    private static byte[] slice(byte[] source, int from, int length) {
        byte[] out = new byte[length];
        System.arraycopy(source, from, out, 0, length);
        return out;
    }

    private static void copyInto(byte[] target, int offset, BigInteger value) {
        byte[] bytes = value.toByteArray();
        int start = bytes.length > 32 ? bytes.length - 32 : 0;
        int length = Math.min(bytes.length, 32);
        System.arraycopy(bytes, start, target, offset + 32 - length, length);
    }
}
