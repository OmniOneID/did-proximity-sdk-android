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

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.upokecenter.cbor.CBORObject;
import com.upokecenter.cbor.CBORType;

import org.omnione.did.proximity.sdk.core.ProximityException;
import org.omnione.did.proximity.sdk.core.cbor.CborTags;
import org.omnione.did.proximity.sdk.core.cbor.CoseKeys;
import org.omnione.did.proximity.sdk.core.crypto.Crypto;
import org.omnione.did.proximity.sdk.core.crypto.Hkdf;
import org.omnione.did.proximity.sdk.core.engagement.SessionTranscripts;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class DeviceAuthVerifier {

    private static final String CONTEXT = "DeviceAuthentication";
    private static final String MAC_CONTEXT = "MAC0";
    private static final byte[] INFO_EMAC_KEY = "EMacKey".getBytes(StandardCharsets.UTF_8);
    private static final int EMAC_KEY_LENGTH = 32;
    private static final String HMAC = "HmacSHA256";

    private static final int COSE_PROTECTED = 0;
    private static final int COSE_UNPROTECTED = 1;
    private static final int COSE_PAYLOAD = 2;
    private static final int COSE_SIGNATURE = 3;
    private static final int COSE_LABEL_ALG = 1;
    private static final int COSE_ALG_HMAC_256 = 5;
    private static final int COSE_ALG_ES256 = -7;
    private static final int COSE_ALG_ES384 = -35;
    private static final int COSE_ALG_ES512 = -36;

    private static final String KEY_DOCUMENTS = "documents";
    private static final String KEY_DOC_TYPE = "docType";
    private static final String KEY_DEVICE_SIGNED = "deviceSigned";
    private static final String KEY_NAME_SPACES = "nameSpaces";
    private static final String KEY_DEVICE_AUTH = "deviceAuth";
    private static final String KEY_DEVICE_SIGNATURE = "deviceSignature";
    private static final String KEY_DEVICE_MAC = "deviceMac";
    private static final String KEY_ISSUER_SIGNED = "issuerSigned";
    private static final String KEY_ISSUER_AUTH = "issuerAuth";
    private static final String KEY_DEVICE_KEY_INFO = "deviceKeyInfo";
    private static final String KEY_DEVICE_KEY = "deviceKey";

    private DeviceAuthVerifier() {}

    @NonNull
    public static List<DeviceAuthResult> verify(@NonNull byte[] deviceResponse,
                                                @NonNull byte[] sessionTranscript,
                                                @Nullable PrivateKey eReaderPrivate) {
        CBORObject response;
        try {
            response = CBORObject.DecodeFromBytes(deviceResponse);
        } catch (RuntimeException e) {
            throw new ProximityException("DeviceResponse is not valid CBOR", e);
        }
        List<DeviceAuthResult> results = new ArrayList<>();
        CBORObject documents = response.get(KEY_DOCUMENTS);
        if (documents == null || documents.getType() != CBORType.Array) {
            return results;
        }
        for (int i = 0; i < documents.size(); i++) {
            results.add(verifyDocument(documents.get(i), sessionTranscript, eReaderPrivate));
        }
        return results;
    }

    @NonNull
    private static DeviceAuthResult verifyDocument(@NonNull CBORObject document,
                                                   @NonNull byte[] sessionTranscript,
                                                   @Nullable PrivateKey eReaderPrivate) {
        try {
            return verifyOrThrow(document, sessionTranscript, eReaderPrivate);
        } catch (ProximityException e) {
            return DeviceAuthResult.failed(DeviceAuthResult.Method.NONE, e.getMessage());
        } catch (RuntimeException e) {
            return DeviceAuthResult.failed(DeviceAuthResult.Method.NONE, String.valueOf(e));
        }
    }

    @NonNull
    private static DeviceAuthResult verifyOrThrow(@NonNull CBORObject document,
                                                  @NonNull byte[] sessionTranscript,
                                                  @Nullable PrivateKey eReaderPrivate) {
        CBORObject deviceSigned = document.get(KEY_DEVICE_SIGNED);
        if (deviceSigned == null || deviceSigned.getType() != CBORType.Map) {
            return DeviceAuthResult.absent("the document carries no deviceSigned");
        }
        CBORObject deviceAuth = deviceSigned.get(KEY_DEVICE_AUTH);
        if (deviceAuth == null || deviceAuth.getType() != CBORType.Map) {
            return DeviceAuthResult.absent("deviceSigned carries no deviceAuth");
        }

        CBORObject signature = deviceAuth.get(KEY_DEVICE_SIGNATURE);
        CBORObject mac = deviceAuth.get(KEY_DEVICE_MAC);
        if (signature == null && mac == null) {
            return DeviceAuthResult.absent(
                    "DeviceAuth carries neither deviceSignature nor deviceMac");
        }
        if (signature != null && mac != null) {
            throw new ProximityException("DeviceAuth carries both deviceSignature and deviceMac");
        }

        CBORObject nameSpaces = deviceSigned.get(KEY_NAME_SPACES);
        if (nameSpaces == null) {
            throw new ProximityException("deviceSigned is missing nameSpaces");
        }
        String docType = text(document.get(KEY_DOC_TYPE), KEY_DOC_TYPE);
        byte[] authenticationBytes =
                deviceAuthenticationBytes(sessionTranscript, docType, nameSpaces);
        ECPublicKey deviceKey = msoDeviceKey(document);

        if (signature != null) {
            return verifySignature(signature, authenticationBytes, deviceKey);
        }
        return verifyMac(mac, authenticationBytes, sessionTranscript, deviceKey, eReaderPrivate);
    }

    @NonNull
    private static byte[] deviceAuthenticationBytes(@NonNull byte[] sessionTranscript,
                                                    @NonNull String docType,
                                                    @NonNull CBORObject nameSpaces) {
        CBORObject transcript;
        try {
            transcript = CBORObject.DecodeFromBytes(sessionTranscript);
        } catch (RuntimeException e) {
            throw new ProximityException("SessionTranscript is not valid CBOR", e);
        }
        CBORObject authentication = CBORObject.NewArray();
        authentication.Add(CONTEXT);
        authentication.Add(transcript);
        authentication.Add(docType);
        authentication.Add(nameSpaces);
        return CborTags.tagged(authentication.EncodeToBytes()).EncodeToBytes();
    }

    @NonNull
    private static DeviceAuthResult verifySignature(@NonNull CBORObject deviceSignature,
                                                    @NonNull byte[] authenticationBytes,
                                                    @NonNull ECPublicKey deviceKey) {
        cose(deviceSignature, KEY_DEVICE_SIGNATURE);
        int alg = alg(deviceSignature);
        String algorithm = signatureAlgorithm(alg);
        byte[] raw = byteString(deviceSignature.get(COSE_SIGNATURE), "deviceSignature signature");
        byte[] protectedHeader =
                byteString(deviceSignature.get(COSE_PROTECTED), "protected header");

        try {
            if (!sign1Verifies(algorithm, protectedHeader, authenticationBytes, raw, deviceKey)) {
                return DeviceAuthResult.failed(DeviceAuthResult.Method.SIGNATURE,
                        "deviceSignature does not verify against the MSO deviceKey");
            }
        } catch (GeneralSecurityException | RuntimeException e) {
            return DeviceAuthResult.failed(DeviceAuthResult.Method.SIGNATURE,
                    "deviceSignature verification failed: " + e);
        }
        return DeviceAuthResult.passed(DeviceAuthResult.Method.SIGNATURE);
    }

    static boolean sign1Verifies(@NonNull String algorithm, @NonNull byte[] protectedHeader,
                                 @NonNull byte[] payload, @NonNull byte[] raw,
                                 @NonNull ECPublicKey key) throws GeneralSecurityException {
        CBORObject structure = CBORObject.NewArray();
        structure.Add("Signature1");
        structure.Add(protectedHeader);
        structure.Add(new byte[0]);
        structure.Add(payload);

        Signature verifier = Signature.getInstance(algorithm, Crypto.provider());
        verifier.initVerify(key);
        verifier.update(structure.EncodeToBytes());
        return verifier.verify(derEncoded(raw));
    }

    @NonNull
    private static DeviceAuthResult verifyMac(@NonNull CBORObject deviceMac,
                                              @NonNull byte[] authenticationBytes,
                                              @NonNull byte[] sessionTranscript,
                                              @NonNull ECPublicKey deviceKey,
                                              @Nullable PrivateKey eReaderPrivate) {
        cose(deviceMac, KEY_DEVICE_MAC);
        if (alg(deviceMac) != COSE_ALG_HMAC_256) {
            throw new ProximityException("deviceMac alg is not HMAC 256/256");
        }
        if (eReaderPrivate == null) {
            throw new ProximityException("no EReaderKey private key to verify deviceMac with");
        }
        byte[] expected = byteString(deviceMac.get(COSE_SIGNATURE), "deviceMac tag");
        byte[] emacKey = emacKey(eReaderPrivate, deviceKey, sessionTranscript);
        try {
            byte[] tag = hmac(emacKey, macStructure(deviceMac, authenticationBytes));
            if (!MessageDigest.isEqual(expected, tag)) {
                return DeviceAuthResult.failed(DeviceAuthResult.Method.MAC,
                        "deviceMac tag does not match the derived EMacKey");
            }
        } finally {
            Arrays.fill(emacKey, (byte) 0x00);
        }
        return DeviceAuthResult.passed(DeviceAuthResult.Method.MAC);
    }

    @NonNull
    private static byte[] macStructure(@NonNull CBORObject deviceMac,
                                       @NonNull byte[] authenticationBytes) {
        CBORObject structure = CBORObject.NewArray();
        structure.Add(MAC_CONTEXT);
        structure.Add(byteString(deviceMac.get(COSE_PROTECTED), "protected header"));
        structure.Add(new byte[0]);
        structure.Add(authenticationBytes);
        return structure.EncodeToBytes();
    }

    @NonNull
    private static byte[] emacKey(@NonNull PrivateKey eReaderPrivate,
                                  @NonNull ECPublicKey deviceKey,
                                  @NonNull byte[] sessionTranscript) {
        byte[] zab = agree(eReaderPrivate, deviceKey);
        try {
            byte[] salt = sha256(SessionTranscripts.taggedBytes(sessionTranscript));
            return Hkdf.derive(salt, zab, INFO_EMAC_KEY, EMAC_KEY_LENGTH);
        } finally {
            Arrays.fill(zab, (byte) 0x00);
        }
    }

    @NonNull
    private static ECPublicKey msoDeviceKey(@NonNull CBORObject document) {
        CBORObject issuerSigned = map(document.get(KEY_ISSUER_SIGNED), KEY_ISSUER_SIGNED);
        CBORObject issuerAuth = issuerSigned.get(KEY_ISSUER_AUTH);
        if (issuerAuth == null || issuerAuth.getType() != CBORType.Array
                || issuerAuth.size() <= COSE_PAYLOAD) {
            throw new ProximityException("issuerAuth is not a COSE_Sign1");
        }
        byte[] payload = byteString(issuerAuth.get(COSE_PAYLOAD), "issuerAuth payload");
        CBORObject mso;
        try {
            mso = CBORObject.DecodeFromBytes(payload);
            if (mso.HasMostOuterTag(CborTags.ENCODED_CBOR)) {
                mso = CBORObject.DecodeFromBytes(CborTags.untag(mso));
            }
        } catch (RuntimeException e) {
            throw new ProximityException("MobileSecurityObject is not valid CBOR", e);
        }
        CBORObject deviceKeyInfo = map(mso.get(KEY_DEVICE_KEY_INFO), KEY_DEVICE_KEY_INFO);
        CBORObject deviceKey = map(deviceKeyInfo.get(KEY_DEVICE_KEY), KEY_DEVICE_KEY);
        return CoseKeys.toPublicKey(deviceKey);
    }

    @NonNull
    private static byte[] derEncoded(@NonNull byte[] raw) {
        if (raw.length == 0 || raw.length % 2 != 0) {
            throw new ProximityException("signature is not a raw r||s pair");
        }
        int half = raw.length / 2;
        byte[] r = integer(Arrays.copyOfRange(raw, 0, half));
        byte[] s = integer(Arrays.copyOfRange(raw, half, raw.length));
        int length = r.length + s.length;
        byte[] header = length < 128
                ? new byte[]{0x30, (byte) length}
                : new byte[]{0x30, (byte) 0x81, (byte) length};
        byte[] der = new byte[header.length + length];
        System.arraycopy(header, 0, der, 0, header.length);
        System.arraycopy(r, 0, der, header.length, r.length);
        System.arraycopy(s, 0, der, header.length + r.length, s.length);
        return der;
    }

    @NonNull
    private static byte[] integer(@NonNull byte[] value) {
        byte[] magnitude = new BigInteger(1, value).toByteArray();
        byte[] encoded = new byte[2 + magnitude.length];
        encoded[0] = 0x02;
        encoded[1] = (byte) magnitude.length;
        System.arraycopy(magnitude, 0, encoded, 2, magnitude.length);
        return encoded;
    }

    @NonNull
    static String signatureAlgorithm(int alg) {
        switch (alg) {
            case COSE_ALG_ES256:
                return "SHA256withECDSA";
            case COSE_ALG_ES384:
                return "SHA384withECDSA";
            case COSE_ALG_ES512:
                return "SHA512withECDSA";
            default:
                throw new ProximityException("unsupported deviceSignature alg " + alg);
        }
    }

    static int alg(@NonNull CBORObject cose) {
        byte[] protectedHeader = byteString(cose.get(COSE_PROTECTED), "protected header");
        CBORObject header;
        try {
            header = CBORObject.DecodeFromBytes(protectedHeader);
        } catch (RuntimeException e) {
            throw new ProximityException("protected header is not valid CBOR", e);
        }
        CBORObject value = header.getType() == CBORType.Map ? header.get(COSE_LABEL_ALG) : null;
        if (value == null && cose.get(COSE_UNPROTECTED).getType() == CBORType.Map) {
            value = cose.get(COSE_UNPROTECTED).get(COSE_LABEL_ALG);
        }
        if (value == null || value.getType() != CBORType.Integer
                || !value.CanValueFitInInt32()) {
            throw new ProximityException("COSE alg is missing or malformed");
        }
        return value.AsInt32Value();
    }

    private static void cose(@NonNull CBORObject value, @NonNull String what) {
        if (value.getType() != CBORType.Array || value.size() != 4) {
            throw new ProximityException(what + " is not a four element COSE structure");
        }
        if (!value.get(COSE_PAYLOAD).isNull()) {
            throw new ProximityException(what + " payload is not null");
        }
    }

    @NonNull
    private static byte[] agree(@NonNull PrivateKey selfPrivate, @NonNull ECPublicKey remote) {
        try {
            KeyAgreement agreement = KeyAgreement.getInstance("ECDH", Crypto.provider());
            agreement.init(selfPrivate);
            agreement.doPhase(remote, true);
            return agreement.generateSecret();
        } catch (Exception e) {
            throw new ProximityException("ECDH key agreement failed", e);
        }
    }

    @NonNull
    private static byte[] hmac(@NonNull byte[] key, @NonNull byte[] message) {
        try {
            Mac mac = Mac.getInstance(HMAC, Crypto.provider());
            mac.init(new SecretKeySpec(key, HMAC));
            return mac.doFinal(message);
        } catch (GeneralSecurityException e) {
            throw new ProximityException("HMAC-SHA256 unavailable", e);
        }
    }

    @NonNull
    private static byte[] sha256(@NonNull byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256", Crypto.provider()).digest(data);
        } catch (GeneralSecurityException e) {
            throw new ProximityException("SHA-256 unavailable", e);
        }
    }

    @NonNull
    private static CBORObject map(@Nullable CBORObject value, @NonNull String what) {
        if (value == null || value.getType() != CBORType.Map) {
            throw new ProximityException(what + " is missing or is not a map");
        }
        return value;
    }

    @NonNull
    private static String text(@Nullable CBORObject value, @NonNull String what) {
        if (value == null || value.getType() != CBORType.TextString) {
            throw new ProximityException(what + " is missing or is not a text string");
        }
        return value.AsString();
    }

    @NonNull
    static byte[] byteString(@Nullable CBORObject value, @NonNull String what) {
        if (value == null || value.getType() != CBORType.ByteString) {
            throw new ProximityException(what + " is missing or is not a byte string");
        }
        return value.GetByteString();
    }
}
