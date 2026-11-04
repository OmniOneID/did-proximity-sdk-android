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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.upokecenter.cbor.CBORObject;

import org.junit.Test;
import org.omnione.did.proximity.sdk.core.cbor.CoseKeys;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.interfaces.ECPublicKey;

public class IssuerAuthVerifierTest {

    private static final String KID = "did:omn:issuer?versionId=1#assert";
    private static final byte[] PAYLOAD = {(byte) 0xD8, 0x18, 0x41, (byte) 0xA0};

    @Test
    public void readsATextKidFromTheUnprotectedHeader() {
        CBORObject unprotected = CBORObject.NewMap();
        unprotected.Add(4, KID);

        byte[] issuerAuth = sign1(CoseKeys.generateEphemeral(), protectedHeader(null), unprotected);

        assertEquals(KID, IssuerAuthVerifier.kid(issuerAuth));
    }

    @Test
    public void readsAByteKidFromTheProtectedHeader() {
        byte[] issuerAuth = sign1(CoseKeys.generateEphemeral(),
                protectedHeader(KID.getBytes(StandardCharsets.UTF_8)), CBORObject.NewMap());

        assertEquals(KID, IssuerAuthVerifier.kid(issuerAuth));
    }

    @Test
    public void theSignedProtectedKidWinsOverTheUnprotectedOne() {
        CBORObject unprotected = CBORObject.NewMap();
        unprotected.Add(4, "did:omn:other#assert");

        byte[] issuerAuth = sign1(CoseKeys.generateEphemeral(),
                protectedHeader(KID.getBytes(StandardCharsets.UTF_8)), unprotected);

        assertEquals(KID, IssuerAuthVerifier.kid(issuerAuth));
    }

    @Test
    public void anUndecodableProtectedHeaderStillFallsBackToTheUnprotectedKid() {
        CBORObject sign1 = CBORObject.NewArray();
        sign1.Add(new byte[]{(byte) 0xFF});
        CBORObject unprotected = CBORObject.NewMap();
        unprotected.Add(4, KID);
        sign1.Add(unprotected);
        sign1.Add(PAYLOAD);
        sign1.Add(new byte[64]);

        assertEquals(KID, IssuerAuthVerifier.kid(sign1.EncodeToBytes()));
    }

    @Test
    public void noKidYieldsNull() {
        byte[] issuerAuth = sign1(CoseKeys.generateEphemeral(),
                protectedHeader(null), CBORObject.NewMap());

        assertNull(IssuerAuthVerifier.kid(issuerAuth));
    }

    @Test
    public void malformedBytesYieldNoKidAndDoNotVerify() {
        byte[] garbage = {0x01, 0x02};
        ECPublicKey key = (ECPublicKey) CoseKeys.generateEphemeral().getPublic();

        assertNull(IssuerAuthVerifier.kid(garbage));
        assertNull(IssuerAuthVerifier.msoDocType(garbage));
        assertFalse(IssuerAuthVerifier.verify(garbage, key));
    }

    @Test
    public void aSignatureByTheIssuerKeyVerifies() {
        KeyPair issuer = CoseKeys.generateEphemeral();
        byte[] issuerAuth = sign1(issuer, protectedHeader(null), CBORObject.NewMap());

        assertTrue(IssuerAuthVerifier.verify(issuerAuth, (ECPublicKey) issuer.getPublic()));
    }

    @Test
    public void aFlippedSignatureByteDoesNotVerify() {
        KeyPair issuer = CoseKeys.generateEphemeral();
        CBORObject issuerAuth = CBORObject.DecodeFromBytes(
                sign1(issuer, protectedHeader(null), CBORObject.NewMap()));
        byte[] signature = issuerAuth.get(3).GetByteString();
        signature[0] ^= 0x01;
        issuerAuth.set(3, CBORObject.FromObject(signature));

        assertFalse(IssuerAuthVerifier.verify(
                issuerAuth.EncodeToBytes(), (ECPublicKey) issuer.getPublic()));
    }

    @Test
    public void aSignatureByAnotherKeyDoesNotVerify() {
        byte[] issuerAuth = sign1(CoseKeys.generateEphemeral(),
                protectedHeader(null), CBORObject.NewMap());
        ECPublicKey other = (ECPublicKey) CoseKeys.generateEphemeral().getPublic();

        assertFalse(IssuerAuthVerifier.verify(issuerAuth, other));
    }

    private static byte[] protectedHeader(byte[] kid) {
        CBORObject header = CBORObject.NewMap();
        header.Add(1, -7);
        if (kid != null) header.Add(4, kid);
        return header.EncodeToBytes();
    }

    private static byte[] sign1(KeyPair issuer, byte[] protectedHeader, CBORObject unprotected) {
        CBORObject structure = CBORObject.NewArray();
        structure.Add("Signature1");
        structure.Add(protectedHeader);
        structure.Add(new byte[0]);
        structure.Add(PAYLOAD);

        CBORObject sign1 = CBORObject.NewArray();
        sign1.Add(protectedHeader);
        sign1.Add(unprotected);
        sign1.Add(PAYLOAD);
        sign1.Add(DeviceAuthVerifierTest.rawSignature(issuer, structure.EncodeToBytes()));
        return sign1.EncodeToBytes();
    }
}
