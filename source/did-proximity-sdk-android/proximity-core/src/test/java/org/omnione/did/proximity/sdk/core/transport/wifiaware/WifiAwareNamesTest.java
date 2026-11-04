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

package org.omnione.did.proximity.sdk.core.transport.wifiaware;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.omnione.did.proximity.sdk.core.engagement.WifiOptions;

public class WifiAwareNamesTest {

    private static byte[] key() {
        byte[] ikm = new byte[32];
        for (int i = 0; i < ikm.length; i++) {
            ikm[i] = (byte) (i + 1);
        }
        return ikm;
    }

    @Test
    public void serviceNameIsTheUpperCaseHexOfSixteenHkdfBytes() {
        assertEquals("550CB963F01FD892150A202E7A2DB698", WifiAwareNames.serviceName(key()));
    }

    @Test
    public void passphraseIsBase64UrlWithoutPaddingOfThirtyTwoHkdfBytes() {
        String passphrase = WifiAwareNames.passphrase(key());

        assertEquals("HhSGUlQ0YNwZfMRIVf_jCKJtmEZuqoCeejuemQjk-H8", passphrase);
        assertEquals(43, passphrase.length());
        assertFalse(passphrase.contains("="));
        assertFalse(passphrase.contains("+"));
        assertFalse(passphrase.contains("/"));
    }

    @Test
    public void derivationIsDeterministicAndKeySpecific() {
        byte[] other = key();
        other[0] ^= 0x01;

        assertEquals(WifiAwareNames.serviceName(key()), WifiAwareNames.serviceName(key()));
        assertNotEquals(WifiAwareNames.serviceName(key()), WifiAwareNames.serviceName(other));
        assertNotEquals(WifiAwareNames.passphrase(key()), WifiAwareNames.passphrase(other));
    }

    @Test
    public void serviceNameIsThirtyTwoUpperCaseHexCharacters() {
        String name = WifiAwareNames.serviceName(key());

        assertEquals(32, name.length());
        assertTrue(name.matches("[0-9A-F]{32}"));
    }

    @Test
    public void anAdvertisedPassphraseWinsOverTheDerivedOne() {
        WifiOptions options = WifiOptions.of(new byte[]{0x14}).withPassphrase("advertised");

        assertEquals("advertised", WifiAwareNames.passphrase(key(), options));
    }

    @Test
    public void withoutAnAdvertisedPassphraseTheDerivedOneIsUsed() {
        WifiOptions options = WifiOptions.of(new byte[]{0x14});

        assertEquals(WifiAwareNames.passphrase(key()), WifiAwareNames.passphrase(key(), options));
    }

    @Test
    public void theSpecExampleCannotBeReproducedBecauseItsInputIsNotPublished() {
        assertNotEquals("ISO/IEC 18013-5 11.3.2 shows 94AB45CDBDEF675162183B12AC35EFAA without"
                        + " the EDeviceKeyBytes it was derived from",
                "94AB45CDBDEF675162183B12AC35EFAA", WifiAwareNames.serviceName(key()));
    }
}
