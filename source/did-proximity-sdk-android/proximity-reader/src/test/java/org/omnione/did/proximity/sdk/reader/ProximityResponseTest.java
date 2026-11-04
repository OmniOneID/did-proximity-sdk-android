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

package org.omnione.did.proximity.sdk.reader;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.omnione.did.proximity.sdk.core.message.Document;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class ProximityResponseTest {

    @Test
    public void theResponseBytesAreCopiedOnConstruction() {
        byte[] deviceResponse = {1, 2, 3};
        byte[] sessionTranscript = {4, 5, 6};

        ProximityResponse response = new ProximityResponse(
                new ArrayList<>(), 0, deviceResponse, sessionTranscript,
                Collections.emptyList());
        deviceResponse[0] = (byte) 0xFF;
        sessionTranscript[0] = (byte) 0xFF;

        assertArrayEquals(new byte[]{1, 2, 3}, response.deviceResponse);
        assertArrayEquals(new byte[]{4, 5, 6}, response.sessionTranscript);
    }

    @Test
    public void aResponseWithoutADocumentIsVerifiedVacuously() {
        ProximityResponse response = new ProximityResponse(
                new ArrayList<>(), 0, new byte[]{1}, new byte[]{2},
                Collections.emptyList());

        assertTrue(response.deviceAuthVerified());
    }

    @Test
    public void aMissingVerdictCountsAsUnverified() {
        List<Document> documents = new ArrayList<>();
        documents.add(null);

        ProximityResponse response = new ProximityResponse(
                documents, 0, new byte[]{1}, new byte[]{2}, Collections.emptyList());

        assertFalse(response.deviceAuthVerified());
    }
}
