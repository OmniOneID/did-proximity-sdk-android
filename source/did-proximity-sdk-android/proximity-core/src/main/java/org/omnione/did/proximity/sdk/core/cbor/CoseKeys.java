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

package org.omnione.did.proximity.sdk.core.cbor;

import androidx.annotation.NonNull;

import com.upokecenter.cbor.CBORObject;

import org.omnione.did.proximity.sdk.core.ProximityException;
import org.omnione.did.proximity.sdk.core.crypto.Crypto;

import java.math.BigInteger;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.util.Arrays;

public final class CoseKeys {

    public static final int LABEL_KTY = 1;
    public static final int LABEL_CRV = -1;
    public static final int LABEL_X = -2;
    public static final int LABEL_Y = -3;

    public static final int KTY_EC2 = 2;
    public static final int CRV_P256 = 1;

    private static final String CURVE = "secp256r1";
    private static final int COORDINATE_LENGTH = 32;

    private CoseKeys() {}

    @NonNull
    public static KeyPair generateEphemeral() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC", Crypto.provider());
            generator.initialize(new ECGenParameterSpec(CURVE));
            return generator.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new ProximityException("failed to generate P-256 key pair", e);
        }
    }

    @NonNull
    public static CBORObject toCoseKey(@NonNull ECPublicKey key) {
        CBORObject coseKey = CBORObject.NewMap();
        coseKey.Add(LABEL_KTY, KTY_EC2);
        coseKey.Add(LABEL_CRV, CRV_P256);
        coseKey.Add(LABEL_X, toFixedLength(key.getW().getAffineX()));
        coseKey.Add(LABEL_Y, toFixedLength(key.getW().getAffineY()));
        return coseKey;
    }

    @NonNull
    public static ECPublicKey toPublicKey(@NonNull CBORObject coseKey) {
        try {
            CBORObject x = coseKey.get(CBORObject.FromObject(LABEL_X));
            CBORObject y = coseKey.get(CBORObject.FromObject(LABEL_Y));
            if (x == null || y == null) {
                throw new ProximityException("COSE_Key is missing x or y");
            }
            ECPoint point = new ECPoint(
                    new BigInteger(1, x.GetByteString()),
                    new BigInteger(1, y.GetByteString()));
            return (ECPublicKey) KeyFactory.getInstance("EC", Crypto.provider())
                    .generatePublic(new ECPublicKeySpec(point, p256Params()));
        } catch (ProximityException e) {
            throw e;
        } catch (Exception e) {
            throw new ProximityException("failed to decode COSE_Key", e);
        }
    }

    @NonNull
    public static ECPublicKey toPublicKey(@NonNull byte[] coseKeyBytes) {
        try {
            return toPublicKey(CBORObject.DecodeFromBytes(coseKeyBytes));
        } catch (ProximityException e) {
            throw e;
        } catch (Exception e) {
            throw new ProximityException("failed to decode COSE_Key", e);
        }
    }

    @NonNull
    private static ECParameterSpec p256Params() {
        try {
            AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC", Crypto.provider());
            parameters.init(new ECGenParameterSpec(CURVE));
            return parameters.getParameterSpec(ECParameterSpec.class);
        } catch (GeneralSecurityException e) {
            throw new ProximityException("P-256 parameters unavailable", e);
        }
    }

    @NonNull
    private static byte[] toFixedLength(@NonNull BigInteger value) {
        byte[] raw = value.toByteArray();
        if (raw.length == COORDINATE_LENGTH) {
            return raw;
        }
        if (raw.length > COORDINATE_LENGTH) {
            return Arrays.copyOfRange(raw, raw.length - COORDINATE_LENGTH, raw.length);
        }
        byte[] padded = new byte[COORDINATE_LENGTH];
        System.arraycopy(raw, 0, padded, COORDINATE_LENGTH - raw.length, raw.length);
        return padded;
    }
}
