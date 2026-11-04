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

package org.omnione.did.proximity.sdk.holder.nfc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.ComponentName;

import androidx.annotation.NonNull;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class NfcEngagementPreferenceTest {

    private static final ComponentName SERVICE = new ComponentName("org.omnione.did.ca",
            "org.omnione.did.proximity.sdk.holder.nfc.MdocHostApduService");

    private static final class FakeRouting implements NfcEngagementPreference.Routing {
        final List<String> calls = new ArrayList<>();
        boolean available = true;
        boolean foregroundAllowed = true;
        boolean accepts = true;

        @Override public boolean available() {
            return available;
        }

        @Override public boolean foregroundPreferenceAllowed() {
            return foregroundAllowed;
        }

        @Override public boolean setPreferred(@NonNull ComponentName service) {
            calls.add("set");
            return accepts;
        }

        @Override public void unsetPreferred() {
            calls.add("unset");
        }
    }

    @Test
    public void aResumedActivityTakesTheForegroundPreference() {
        FakeRouting routing = new FakeRouting();

        assertTrue(NfcEngagementPreference.prefer(routing, SERVICE));
        assertEquals(Arrays.asList("set"), routing.calls);
    }

    @Test
    public void releaseGivesTheRoutingBackToOtherApps() {
        FakeRouting routing = new FakeRouting();

        NfcEngagementPreference.prefer(routing, SERVICE);
        NfcEngagementPreference.release(routing);

        assertEquals("onPause must hand the routing back; without it a third-party wallet can"
                        + " never win the AID while this app is merely installed",
                Arrays.asList("set", "unset"), routing.calls);
    }

    @Test
    public void releaseKeepsThePreferenceWhileAnNfcDataSessionIsLive() {
        FakeRouting routing = new FakeRouting();

        NfcEngagementPreference.prefer(routing, SERVICE);
        NfcEngagementPreference.release(routing, true);

        assertEquals("unsetting mid-transaction could unbind the HCE service that still owes"
                        + " the reader its deferred ENVELOPE response",
                Arrays.asList("set"), routing.calls);

        NfcEngagementPreference.release(routing, false);
        assertEquals(Arrays.asList("set", "unset"), routing.calls);
    }

    @Test
    public void releaseIsSafeWhenNothingWasEverPreferred() {
        FakeRouting routing = new FakeRouting();

        NfcEngagementPreference.release(routing);

        assertEquals(Arrays.asList("unset"), routing.calls);
    }

    @Test
    public void aDeviceWithoutUsableNfcIsANoOpRatherThanAFailure() {
        FakeRouting routing = new FakeRouting();
        routing.available = false;

        assertFalse(NfcEngagementPreference.prefer(routing, SERVICE));
        NfcEngagementPreference.release(routing);

        assertTrue("an unusable adapter must never touch the platform routing",
                routing.calls.isEmpty());
    }

    @Test
    public void aMissingAdapterIsANoOpRatherThanACrash() {
        NfcEngagementPreference.Routing absent = null;
        assertFalse(NfcEngagementPreference.prefer(absent, SERVICE));
        NfcEngagementPreference.release(absent);
    }

    @Test
    public void aPlatformThatRefusesForegroundPreferenceIsRespected() {
        FakeRouting routing = new FakeRouting();
        routing.foregroundAllowed = false;

        assertFalse(NfcEngagementPreference.prefer(routing, SERVICE));
        assertTrue(routing.calls.isEmpty());
    }

    @Test
    public void aRejectedPreferenceIsReportedWithoutThrowing() {
        FakeRouting routing = new FakeRouting();
        routing.accepts = false;

        assertFalse(NfcEngagementPreference.prefer(routing, SERVICE));
        assertEquals(1, routing.calls.size());
    }

    @Test
    public void repeatedResumePauseCyclesStayBalanced() {
        FakeRouting routing = new FakeRouting();

        for (int i = 0; i < 3; i++) {
            NfcEngagementPreference.prefer(routing, SERVICE);
            NfcEngagementPreference.release(routing);
        }

        long sets = routing.calls.stream().filter(c -> c.equals("set")).count();
        long unsets = routing.calls.stream().filter(c -> c.equals("unset")).count();
        assertEquals("every resume must be matched by a pause", sets, unsets);
        assertEquals(3L, sets);
    }
}
