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

package org.omnione.did.proximity.sdk.core.session;

import static org.junit.Assert.assertNotNull;

import com.upokecenter.cbor.CBORObject;

import org.omnione.did.proximity.sdk.core.cbor.CoseKeys;
import org.omnione.did.proximity.sdk.core.crypto.Crypto;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.security.AlgorithmParameters;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPrivateKeySpec;

public final class IsoAnnexD {

    public static final String EPHEMERAL_DEVICE_X =
            "5a88d182bce5f42efa59943f33359d2e8a968ff289d93e5fa444b624343167fe";
    public static final String EPHEMERAL_DEVICE_Y =
            "b16e8cf858ddc7690407ba61d4c338237a8cfcf3de6aa672fc60a557aa32fc67";
    public static final String EPHEMERAL_DEVICE_D =
            "c1917a1579949a042f1ba9fc53a2df9b1bc47adf31c10f813ed75702d1c1f136";

    public static final String EPHEMERAL_READER_X =
            "60e3392385041f51403051f2415531cb56dd3f999c71687013aac6768bc8187e";
    public static final String EPHEMERAL_READER_Y =
            "e58deb8fdbe907f7dd5368245551a34796f7d2215c440c339bb0f7b67beccdfa";
    public static final String EPHEMERAL_READER_D =
            "de3b4b9e5f72dd9b58406ae3091434da48a6f9fd010d88fcb0958e2cebec947c";

    static final String SK_DEVICE =
            "81d170e07fbdac93c1a676242c2576124a380d87bb73ed9ce4834de2272cf409";
    static final String SK_READER =
            "58d277d8719e62a1561d248f403f477e9e6c37bf5d5fc5126f8f4c727c22dfc9";

    static final String SESSION_TRANSCRIPT_BYTES_PREFIX = "d818590241";

    public static final String QR_DEVICE_ENGAGEMENT_D_3_1 =
            "a30063312e30018201d818584ba4010220012158205a88d182bce5f42efa59943f33359d2e8a968ff2"
                    + "89d93e5fa444b624343167fe225820b16e8cf858ddc7690407ba61d4c338237a8cfcf3de6"
                    + "aa672fc60a557aa32fc670281830201a300f401f50b5045efef742b2c4837a9a3b0e1d05a"
                    + "6917";

    public static final String NFC_HANDOVER_REQUEST_D_3_2 =
            "91022548721591020263720102110204616301013000110206616301036e6663005102046163010157"
                    + "001a201e016170706c69636174696f6e2f766e642e626c7565746f6f74682e6c652e6f6f62"
                    + "30081b28078080bf2801021c021107c832fff6d26fa0beb34dfcd555d4823a1c1101036973"
                    + "6f2e6f72673a31383031333a6e66636e6663015a172b016170706c69636174696f6e2f766e"
                    + "642e7766612e6e616e57030101032302001324fec9a70b97ac9684a4e326176ef5b981c5e8"
                    + "533e5f00298cfccbc35e700a6b020414";

    public static final String NFC_HANDOVER_SELECT_D_3_3 =
            "91020f487315d10209616301013001046d646f631a200c016170706c69636174696f6e2f766e642e62"
                    + "6c7565746f6f74682e6c652e6f6f6230081b28128b37282801021c015c1e580469736f2e6f"
                    + "72673a31383031333a646576696365656e676167656d656e746d646f63a20063312e300182"
                    + "01d818584ba4010220012158205a88d182bce5f42efa59943f33359d2e8a968ff289d93e5f"
                    + "a444b624343167fe225820b16e8cf858ddc7690407ba61d4c338237a8cfcf3de6aa672fc60"
                    + "a557aa32fc67";

    static final String SESSION_TRANSCRIPT =
            "/iso18013-5-annex-d5-1-session-transcript.hex";
    static final String MDOC_REQUEST_CIPHERTEXT =
            "/iso18013-5-annex-d5-1-mdoc-request-ciphertext.hex";
    static final String MDOC_REQUEST_PLAINTEXT =
            "/iso18013-5-annex-d5-1-mdoc-request-plaintext.hex";
    static final String MDOC_RESPONSE_CIPHERTEXT =
            "/iso18013-5-annex-d5-1-mdoc-response-ciphertext.hex";
    static final String MDOC_RESPONSE_PLAINTEXT =
            "/iso18013-5-annex-d5-1-mdoc-response-plaintext.hex";

    private IsoAnnexD() {
    }

    static SessionKeys sessionKeys() {
        return SessionKeys.derive(
                privateKey(EPHEMERAL_DEVICE_D),
                publicKey(EPHEMERAL_READER_X, EPHEMERAL_READER_Y),
                resource(SESSION_TRANSCRIPT));
    }

    static byte[] resource(String name) {
        try (InputStream stream = IsoAnnexD.class.getResourceAsStream(name)) {
            assertNotNull(name, stream);
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int read;
            while ((read = stream.read(chunk)) != -1) {
                buffer.write(chunk, 0, read);
            }
            return hex(new String(buffer.toByteArray(), "US-ASCII").replaceAll("\\s", ""));
        } catch (IOException e) {
            throw new AssertionError(name, e);
        }
    }

    public static ECPublicKey publicKey(String x, String y) {
        CBORObject coseKey = CBORObject.NewMap();
        coseKey.Add(CoseKeys.LABEL_KTY, CoseKeys.KTY_EC2);
        coseKey.Add(CoseKeys.LABEL_CRV, CoseKeys.CRV_P256);
        coseKey.Add(CoseKeys.LABEL_X, hex(x));
        coseKey.Add(CoseKeys.LABEL_Y, hex(y));
        return CoseKeys.toPublicKey(coseKey);
    }

    public static PrivateKey privateKey(String d) {
        try {
            AlgorithmParameters parameters =
                    AlgorithmParameters.getInstance("EC", Crypto.provider());
            parameters.init(new ECGenParameterSpec("secp256r1"));
            ECParameterSpec spec = parameters.getParameterSpec(ECParameterSpec.class);
            return KeyFactory.getInstance("EC", Crypto.provider())
                    .generatePrivate(new ECPrivateKeySpec(new BigInteger(1, hex(d)), spec));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    public static byte[] hex(String value) {
        byte[] bytes = new byte[value.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
        }
        return bytes;
    }
}
