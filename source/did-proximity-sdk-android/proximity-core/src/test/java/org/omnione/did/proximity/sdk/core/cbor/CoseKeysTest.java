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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import com.upokecenter.cbor.CBORObject;

import org.junit.Test;
import org.omnione.did.proximity.sdk.core.ProximityException;

import java.security.KeyPair;
import java.security.interfaces.ECPublicKey;

public class CoseKeysTest {

    @Test
    public void coseKeyRoundTripPreservesPublicKey() {
        KeyPair pair = CoseKeys.generateEphemeral();
        ECPublicKey original = (ECPublicKey) pair.getPublic();

        CBORObject coseKey = CoseKeys.toCoseKey(original);
        ECPublicKey restored = CoseKeys.toPublicKey(coseKey);

        assertEquals(original.getW().getAffineX(), restored.getW().getAffineX());
        assertEquals(original.getW().getAffineY(), restored.getW().getAffineY());
    }

    @Test
    public void coseKeyUsesEc2P256Labels() {
        CBORObject coseKey = CoseKeys.toCoseKey((ECPublicKey) CoseKeys.generateEphemeral().getPublic());

        assertEquals(2, coseKey.get(CBORObject.FromObject(1)).AsInt32Value());
        assertEquals(1, coseKey.get(CBORObject.FromObject(-1)).AsInt32Value());
        assertEquals(32, coseKey.get(CBORObject.FromObject(-2)).GetByteString().length);
        assertEquals(32, coseKey.get(CBORObject.FromObject(-3)).GetByteString().length);
    }

    @Test
    public void taggedBytesRoundTrip() {
        byte[] payload = CBORObject.NewMap().EncodeToBytes();

        CBORObject tagged = CborTags.tagged(payload);

        assertEquals(CborTags.ENCODED_CBOR, tagged.getMostOuterTag().ToInt32Checked());
        assertArrayEquals(payload, CborTags.untag(tagged));
    }

    @Test
    public void untagRejectsBareByteString() {
        CBORObject bare = CBORObject.FromObject(new byte[] {1, 2, 3});

        assertThrows(ProximityException.class, () -> CborTags.untag(bare));
    }

    @Test
    public void untagRejectsWrongOuterTag() {
        CBORObject wrongTag = CBORObject.FromObject(new byte[] {1, 2, 3}).WithTag(2);

        assertThrows(ProximityException.class, () -> CborTags.untag(wrongTag));
    }
}
