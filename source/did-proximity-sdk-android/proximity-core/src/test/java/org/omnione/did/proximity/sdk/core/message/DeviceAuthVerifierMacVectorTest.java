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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.upokecenter.cbor.CBORObject;

import org.junit.Test;
import org.omnione.did.proximity.sdk.core.cbor.CborTags;

import java.math.BigInteger;
import java.security.AlgorithmParameters;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.util.List;

import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPrivateKeySpec;

public class DeviceAuthVerifierMacVectorTest {

    private static final String SESSION_TRANSCRIPT_HEX =
            "83d8185858a20063312e30018201d818584ba4010220012158205a88d182bce5f42efa59943f33359d2e8a968ff289d9"
                    + "3e5fa444b624343167fe225820b16e8cf858ddc7690407ba61d4c338237a8cfcf3de6aa672fc60a557aa32fc67d81858"
                    + "4ba40102200121582060e3392385041f51403051f2415531cb56dd3f999c71687013aac6768bc8187e225820e58deb8f"
                    + "dbe907f7dd5368245551a34796f7d2215c440c339bb0f7b67beccdfa8258c391020f487315d10209616301013001046d"
                    + "646f631a200c016170706c69636174696f6e2f766e642e626c7565746f6f74682e6c652e6f6f6230081b28128b372828"
                    + "01021c015c1e580469736f2e6f72673a31383031333a646576696365656e676167656d656e746d646f63a20063312e30"
                    + "018201d818584ba4010220012158205a88d182bce5f42efa59943f33359d2e8a968ff289d93e5fa444b624343167fe22"
                    + "5820b16e8cf858ddc7690407ba61d4c338237a8cfcf3de6aa672fc60a557aa32fc6758cd910225487215910202637201"
                    + "02110204616301013000110206616301036e6663005102046163010157001a201e016170706c69636174696f6e2f766e"
                    + "642e626c7565746f6f74682e6c652e6f6f6230081b28078080bf2801021c021107c832fff6d26fa0beb34dfcd555d482"
                    + "3a1c11010369736f2e6f72673a31383031333a6e66636e6663015a172b016170706c69636174696f6e2f766e642e7766"
                    + "612e6e616e57030101032302001324fec9a70b97ac9684a4e326176ef5b981c5e8533e5f00298cfccbc35e700a6b0204"
                    + "14";

    private static final String DEVICE_AUTH_HEX =
            "a1696465766963654d61638443a10105a0f65820"
                    + "e99521a85ad7891b806a07f8b5388a332d92c189a7bf293ee1f543405ae6824d";

    private static final String DEVICE_NAME_SPACES_BYTES_HEX = "d81841a0";

    private static final String DEVICE_KEY_X =
            "96313d6c63e24e3372742bfdb1a33ba2c897dcd68ab8c753e4fbd48dca6b7f9a";
    private static final String DEVICE_KEY_Y =
            "1fb3269edd418857de1b39a4e4a44b92fa484caa722c228288f01d0c03a2c3d6";
    private static final String E_READER_KEY_D =
            "de3b4b9e5f72dd9b58406ae3091434da48a6f9fd010d88fcb0958e2cebec947c";

    private static final String DOC_TYPE = "org.iso.18013.5.1.mDL";

    @Test
    public void theStandardMacVectorVerifies() {
        List<DeviceAuthResult> results = DeviceAuthVerifier.verify(
                deviceResponse(hex(DEVICE_AUTH_HEX)),
                hex(SESSION_TRANSCRIPT_HEX),
                eReaderPrivateKey());

        assertEquals(1, results.size());
        DeviceAuthResult result = results.get(0);
        assertEquals(result.detail, DeviceAuthResult.Method.MAC, result.method);
        assertTrue(String.valueOf(result.detail), result.verified());
    }

    @Test
    public void aFlippedTagByteFailsTheStandardMacVector() {
        byte[] deviceAuth = hex(DEVICE_AUTH_HEX);
        deviceAuth[deviceAuth.length - 1] ^= 0x01;

        List<DeviceAuthResult> results = DeviceAuthVerifier.verify(
                deviceResponse(deviceAuth), hex(SESSION_TRANSCRIPT_HEX), eReaderPrivateKey());

        assertEquals(DeviceAuthResult.Outcome.INVALID, results.get(0).outcome);
        assertEquals(DeviceAuthResult.Method.MAC, results.get(0).method);
    }

    @Test
    public void theSessionTranscriptIsBoundIntoTheStandardMacVector() {
        byte[] transcript = hex(SESSION_TRANSCRIPT_HEX);
        transcript[transcript.length - 1] ^= 0x01;

        List<DeviceAuthResult> results = DeviceAuthVerifier.verify(
                deviceResponse(hex(DEVICE_AUTH_HEX)), transcript, eReaderPrivateKey());

        assertNotNull(results.get(0).detail);
        assertEquals(DeviceAuthResult.Outcome.INVALID, results.get(0).outcome);
    }

    @Test
    public void theVectorTranscriptSurvivesAReencodeUnchanged() {
        byte[] transcript = hex(SESSION_TRANSCRIPT_HEX);

        assertArrayEquals(transcript,
                CBORObject.DecodeFromBytes(transcript).EncodeToBytes());
    }

    @Test
    public void withoutTheReaderPrivateKeyTheMacCannotBeVerified() {
        List<DeviceAuthResult> results = DeviceAuthVerifier.verify(
                deviceResponse(hex(DEVICE_AUTH_HEX)), hex(SESSION_TRANSCRIPT_HEX), null);

        assertEquals(DeviceAuthResult.Outcome.INVALID, results.get(0).outcome);
        assertEquals(DeviceAuthResult.Method.NONE, results.get(0).method);
    }

    private static byte[] deviceResponse(byte[] deviceAuth) {
        CBORObject deviceSigned = CBORObject.NewMap();
        deviceSigned.Add("nameSpaces",
                CBORObject.DecodeFromBytes(hex(DEVICE_NAME_SPACES_BYTES_HEX)));
        deviceSigned.Add("deviceAuth", CBORObject.DecodeFromBytes(deviceAuth));

        CBORObject document = CBORObject.NewMap();
        document.Add("docType", DOC_TYPE);
        document.Add("issuerSigned", issuerSigned());
        document.Add("deviceSigned", deviceSigned);

        CBORObject documents = CBORObject.NewArray();
        documents.Add(document);

        CBORObject response = CBORObject.NewMap();
        response.Add("version", "1.0");
        response.Add("documents", documents);
        response.Add("status", 0);
        return response.EncodeToBytes();
    }

    private static CBORObject issuerSigned() {
        CBORObject deviceKey = CBORObject.NewMap();
        deviceKey.Add(1, 2);
        deviceKey.Add(-1, 1);
        deviceKey.Add(-2, hex(DEVICE_KEY_X));
        deviceKey.Add(-3, hex(DEVICE_KEY_Y));

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

        CBORObject issuerSigned = CBORObject.NewMap();
        issuerSigned.Add("nameSpaces", CBORObject.NewMap());
        issuerSigned.Add("issuerAuth", issuerAuth);
        return issuerSigned;
    }

    private static PrivateKey eReaderPrivateKey() {
        try {
            AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC");
            parameters.init(new ECGenParameterSpec("secp256r1"));
            ECParameterSpec spec = parameters.getParameterSpec(ECParameterSpec.class);
            return KeyFactory.getInstance("EC").generatePrivate(
                    new ECPrivateKeySpec(new BigInteger(E_READER_KEY_D, 16), spec));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] hex(String value) {
        byte[] out = new byte[value.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }
}
