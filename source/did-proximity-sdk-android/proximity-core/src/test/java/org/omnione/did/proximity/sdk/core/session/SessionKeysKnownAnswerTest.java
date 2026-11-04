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
import static org.junit.Assert.assertFalse;

import org.junit.Test;
import org.omnione.did.proximity.sdk.core.engagement.SessionTranscripts;

import java.util.Arrays;

public class SessionKeysKnownAnswerTest {

    @Test
    public void mdocSideDerivesTheIsoSessionKeys() {
        SessionKeys keys = SessionKeys.derive(
                IsoAnnexD.privateKey(IsoAnnexD.EPHEMERAL_DEVICE_D),
                IsoAnnexD.publicKey(IsoAnnexD.EPHEMERAL_READER_X, IsoAnnexD.EPHEMERAL_READER_Y),
                IsoAnnexD.resource(IsoAnnexD.SESSION_TRANSCRIPT));

        assertArrayEquals(IsoAnnexD.hex(IsoAnnexD.SK_DEVICE), keys.deviceKey());
        assertArrayEquals(IsoAnnexD.hex(IsoAnnexD.SK_READER), keys.readerKey());
    }

    @Test
    public void mdocReaderSideDerivesTheIsoSessionKeys() {
        SessionKeys keys = SessionKeys.derive(
                IsoAnnexD.privateKey(IsoAnnexD.EPHEMERAL_READER_D),
                IsoAnnexD.publicKey(IsoAnnexD.EPHEMERAL_DEVICE_X, IsoAnnexD.EPHEMERAL_DEVICE_Y),
                IsoAnnexD.resource(IsoAnnexD.SESSION_TRANSCRIPT));

        assertArrayEquals(IsoAnnexD.hex(IsoAnnexD.SK_DEVICE), keys.deviceKey());
        assertArrayEquals(IsoAnnexD.hex(IsoAnnexD.SK_READER), keys.readerKey());
    }

    @Test
    public void infoLabelsAreNotSwapped() {
        SessionKeys keys = IsoAnnexD.sessionKeys();

        assertFalse(Arrays.equals(IsoAnnexD.hex(IsoAnnexD.SK_READER), keys.deviceKey()));
        assertFalse(Arrays.equals(IsoAnnexD.hex(IsoAnnexD.SK_DEVICE), keys.readerKey()));
    }

    @Test
    public void taggedTranscriptMatchesTheIsoSessionTranscriptBytes() {
        byte[] transcript = IsoAnnexD.resource(IsoAnnexD.SESSION_TRANSCRIPT);

        byte[] tagged = SessionTranscripts.taggedBytes(transcript);

        assertArrayEquals(IsoAnnexD.hex(IsoAnnexD.SESSION_TRANSCRIPT_BYTES_PREFIX),
                Arrays.copyOf(tagged, 5));
        assertArrayEquals(transcript, Arrays.copyOfRange(tagged, 5, tagged.length));
    }
}
