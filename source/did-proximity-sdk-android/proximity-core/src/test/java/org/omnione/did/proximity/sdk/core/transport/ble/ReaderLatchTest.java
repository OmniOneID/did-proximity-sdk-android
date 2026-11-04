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

package org.omnione.did.proximity.sdk.core.transport.ble;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ReaderLatchTest {

    private static final int DEFAULT_MTU = 23;
    private static final String READER = "reader";
    private static final String EARPHONES = "earphones";

    private final ReaderLatch<String> latch = new ReaderLatch<>(DEFAULT_MTU);

    @Test
    public void noReaderIsLatchedBeforeAnyClaim() {
        assertNull(latch.current());
        assertFalse(latch.holds(READER));
    }

    @Test
    public void theFirstClaimLatchesTheReader() {
        assertEquals(ReaderLatch.Outcome.LATCHED, latch.claim(READER));

        assertEquals(READER, latch.current());
        assertTrue(latch.holds(READER));
    }

    @Test
    public void aRepeatClaimFromTheLatchedReaderIsAccepted() {
        latch.claim(READER);

        assertEquals(ReaderLatch.Outcome.ACCEPTED, latch.claim(READER));
        assertEquals(READER, latch.current());
    }

    @Test
    public void aClaimFromAnotherDeviceIsRejectedAndLeavesTheReaderLatched() {
        latch.claim(READER);

        assertEquals(ReaderLatch.Outcome.REJECTED, latch.claim(EARPHONES));
        assertEquals(READER, latch.current());
        assertFalse(latch.holds(EARPHONES));
    }

    @Test
    public void releasingAnUnlatchedDeviceChangesNothing() {
        latch.claim(READER);

        assertFalse(latch.release(EARPHONES));
        assertEquals(READER, latch.current());
    }

    @Test
    public void releasingTheLatchedReaderFreesTheLatch() {
        latch.claim(READER);

        assertTrue(latch.release(READER));
        assertNull(latch.current());
    }

    @Test
    public void aNewReaderCanLatchAfterTheFirstOneIsReleased() {
        latch.claim(READER);
        latch.release(READER);

        assertEquals(ReaderLatch.Outcome.LATCHED, latch.claim(EARPHONES));
    }

    @Test
    public void theMtuStartsAtTheDefault() {
        assertEquals(DEFAULT_MTU, latch.mtu());
    }

    @Test
    public void theLatchedReaderNegotiatesTheMtu() {
        latch.claim(READER);

        latch.noteMtu(READER, 517);

        assertEquals(517, latch.mtu());
    }

    @Test
    public void anMtuNegotiatedBeforeTheClaimIsAdoptedOnLatch() {
        latch.noteMtu(READER, 517);

        latch.claim(READER);

        assertEquals(517, latch.mtu());
    }

    @Test
    public void anMtuNegotiatedByAnotherDeviceIsNotAdopted() {
        latch.noteMtu(EARPHONES, 517);

        latch.claim(READER);

        assertEquals(DEFAULT_MTU, latch.mtu());
    }

    @Test
    public void anotherDeviceCannotChangeTheMtuOfTheLatchedReader() {
        latch.claim(READER);
        latch.noteMtu(READER, 517);

        latch.noteMtu(EARPHONES, 64);

        assertEquals(517, latch.mtu());
    }

    @Test
    public void releasingTheReaderRestoresTheDefaultMtu() {
        latch.claim(READER);
        latch.noteMtu(READER, 517);

        latch.release(READER);

        assertEquals(DEFAULT_MTU, latch.mtu());
    }

    @Test
    public void clearDropsTheLatchAndTheNegotiatedMtu() {
        latch.noteMtu(EARPHONES, 128);
        latch.claim(READER);
        latch.noteMtu(READER, 517);

        latch.clear();

        assertNull(latch.current());
        assertEquals(DEFAULT_MTU, latch.mtu());
    }

    @Test
    public void clearAlsoDropsAPendingMtu() {
        latch.noteMtu(READER, 517);

        latch.clear();
        latch.claim(READER);

        assertEquals(DEFAULT_MTU, latch.mtu());
    }
}
