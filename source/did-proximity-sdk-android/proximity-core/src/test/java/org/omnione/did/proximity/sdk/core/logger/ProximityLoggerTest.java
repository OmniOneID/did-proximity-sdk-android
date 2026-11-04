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

package org.omnione.did.proximity.sdk.core.logger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class ProximityLoggerTest {

    @Before
    @After
    public void resetToTheDefault() {
        ProximityLogger.getInstance().disable();
    }

    @Test
    public void loggingIsDisabledByDefault() {
        assertFalse(ProximityLogger.getInstance().isEnabled());
    }

    @Test
    public void enableTurnsLoggingOn() {
        ProximityLogger.getInstance().enable();

        assertTrue(ProximityLogger.getInstance().isEnabled());
    }

    @Test
    public void disableTurnsLoggingOffAgain() {
        ProximityLogger.getInstance().enable();

        ProximityLogger.getInstance().disable();

        assertFalse(ProximityLogger.getInstance().isEnabled());
    }

    @Test
    public void everyCallerSharesOneInstance() {
        assertSame(ProximityLogger.getInstance(), ProximityLogger.getInstance());
    }

    @Test
    public void aDisabledLoggerNeverReachesTheAndroidLog() {
        ProximityLogger logger = ProximityLogger.getInstance();

        logger.d("d");
        logger.e("e");
        logger.i("i");
        logger.v("v");
    }

    @Test
    public void aDisabledLoggerToleratesANullMessage() {
        ProximityLogger.getInstance().d(null);
    }

    @Test
    public void theCallerPrefixNamesTheCallingFileAndMethod() {
        assertEquals("[ ProximityLoggerTest :: theCallerPrefixNamesTheCallingFileAndMethod ] ",
                ProximityLogger.callerPrefix());
    }

    @Test
    public void theCallerPrefixSkipsTheAndroidStackTraceFrames() {
        StackTraceElement[] androidTrace = {
                new StackTraceElement("dalvik.system.VMStack",
                        "getThreadStackTrace", "VMStack.java", -2),
                new StackTraceElement("java.lang.Thread",
                        "getStackTrace", "Thread.java", 1841),
                new StackTraceElement(ProximityLogger.class.getName(),
                        "callerPrefix", "ProximityLogger.java", 92),
                new StackTraceElement(ProximityLogger.class.getName(),
                        "write", "ProximityLogger.java", 84),
                new StackTraceElement(ProximityLogger.class.getName(),
                        "d", "ProximityLogger.java", 64),
                new StackTraceElement(
                        "org.omnione.did.proximity.sdk.core.transport.ble.GattServerTransport$1",
                        "onServiceAdded", "GattServerTransport.java", 372),
        };

        assertEquals("[ GattServerTransport :: onServiceAdded ] ",
                ProximityLogger.callerPrefix(androidTrace));
    }

    @Test
    public void theCallerPrefixIsEmptyWhenTheLoggerIsNotOnTheStack() {
        StackTraceElement[] trace = {
                new StackTraceElement("dalvik.system.VMStack",
                        "getThreadStackTrace", "VMStack.java", -2),
                new StackTraceElement("java.lang.Thread",
                        "getStackTrace", "Thread.java", 1841),
        };

        assertEquals("", ProximityLogger.callerPrefix(trace));
    }

    @Test
    public void aLongMessageIsSplitIntoLoggableChunks() {
        StringBuilder message = new StringBuilder();
        for (int i = 0; i < 9000; i++) {
            message.append('x');
        }

        assertEquals(3, ProximityLogger.chunk(message.toString()).size());
        assertEquals(4000, ProximityLogger.chunk(message.toString()).get(0).length());
        assertEquals(1000, ProximityLogger.chunk(message.toString()).get(2).length());
    }

    @Test
    public void aShortMessageStaysASingleChunk() {
        assertEquals(1, ProximityLogger.chunk("short").size());
        assertEquals("short", ProximityLogger.chunk("short").get(0));
    }

    @Test
    public void aNullMessageChunksToAnEmptyText() {
        assertEquals(1, ProximityLogger.chunk(null).size());
        assertEquals("null", ProximityLogger.chunk(null).get(0));
    }
}
