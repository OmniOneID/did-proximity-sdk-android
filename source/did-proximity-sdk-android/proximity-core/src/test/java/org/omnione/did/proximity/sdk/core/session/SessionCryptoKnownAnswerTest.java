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

import static org.junit.Assert.assertArrayEquals;

import org.junit.Test;

public class SessionCryptoKnownAnswerTest {

    @Test
    public void mdocSideDecryptsTheIsoMdocRequest() {
        SessionCrypto crypto = SessionCrypto.forDevice(IsoAnnexD.sessionKeys());

        byte[] plaintext = crypto.decrypt(IsoAnnexD.resource(IsoAnnexD.MDOC_REQUEST_CIPHERTEXT));

        assertArrayEquals(IsoAnnexD.resource(IsoAnnexD.MDOC_REQUEST_PLAINTEXT), plaintext);
    }

    @Test
    public void mdocReaderSideDecryptsTheIsoMdocResponse() {
        SessionCrypto crypto = SessionCrypto.forReader(IsoAnnexD.sessionKeys());

        byte[] plaintext = crypto.decrypt(IsoAnnexD.resource(IsoAnnexD.MDOC_RESPONSE_CIPHERTEXT));

        assertArrayEquals(IsoAnnexD.resource(IsoAnnexD.MDOC_RESPONSE_PLAINTEXT), plaintext);
    }

    @Test
    public void mdocReaderSideEncryptsToTheIsoMdocRequest() {
        SessionCrypto crypto = SessionCrypto.forReader(IsoAnnexD.sessionKeys());

        byte[] ciphertext = crypto.encrypt(IsoAnnexD.resource(IsoAnnexD.MDOC_REQUEST_PLAINTEXT));

        assertArrayEquals(IsoAnnexD.resource(IsoAnnexD.MDOC_REQUEST_CIPHERTEXT), ciphertext);
    }

    @Test
    public void mdocSideEncryptsToTheIsoMdocResponse() {
        SessionCrypto crypto = SessionCrypto.forDevice(IsoAnnexD.sessionKeys());

        byte[] ciphertext = crypto.encrypt(IsoAnnexD.resource(IsoAnnexD.MDOC_RESPONSE_PLAINTEXT));

        assertArrayEquals(IsoAnnexD.resource(IsoAnnexD.MDOC_RESPONSE_CIPHERTEXT), ciphertext);
    }
}
