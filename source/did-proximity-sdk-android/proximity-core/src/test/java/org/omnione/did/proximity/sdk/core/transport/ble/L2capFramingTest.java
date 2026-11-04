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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

import org.junit.Test;
import org.omnione.did.proximity.sdk.core.ProximityException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;

public class L2capFramingTest {

    private static InputStream stream(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.write(part, 0, part.length);
        }
        return new ByteArrayInputStream(out.toByteArray());
    }

    private static byte[] bytes(int... values) {
        byte[] out = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = (byte) values[i];
        }
        return out;
    }

    @Test
    public void anEmptyBodyFramesAsFourZeroBytes() {
        assertArrayEquals(bytes(0, 0, 0, 0), L2capFraming.frame(new byte[0]));
    }

    @Test
    public void theHeaderIsBigEndianAndExcludesItself() {
        byte[] body = new byte[300];
        byte[] frame = L2capFraming.frame(body);
        assertEquals(304, frame.length);
        assertArrayEquals(bytes(0, 0, 0x01, 0x2C), new byte[]{frame[0], frame[1], frame[2], frame[3]});
    }

    @Test
    public void aFrameRoundTrips() throws Exception {
        byte[] body = bytes(0xA2, 0x01, 0x02, 0x03);
        assertArrayEquals(body, L2capFraming.readFrame(stream(L2capFraming.frame(body))));
    }

    @Test
    public void twoConsecutiveFramesReadInOrder() throws Exception {
        InputStream in = stream(L2capFraming.frame(bytes(1)), L2capFraming.frame(bytes(2, 2)));
        assertArrayEquals(bytes(1), L2capFraming.readFrame(in));
        assertArrayEquals(bytes(2, 2), L2capFraming.readFrame(in));
        assertNull(L2capFraming.readFrame(in));
    }

    @Test
    public void eofBeforeTheFirstByteIsNull() throws Exception {
        assertNull(L2capFraming.readFrame(stream()));
    }

    @Test
    public void aTruncatedHeaderIsAnError() {
        assertThrows(ProximityException.class,
                () -> L2capFraming.readFrame(stream(bytes(0, 0))));
    }

    @Test
    public void aTruncatedBodyIsAnError() {
        assertThrows(ProximityException.class,
                () -> L2capFraming.readFrame(stream(bytes(0, 0, 0, 4, 1, 2))));
    }

    @Test
    public void aFrameLongerThanOneMebibyteIsRejected() {
        assertThrows(ProximityException.class,
                () -> L2capFraming.readFrame(stream(bytes(0, 0x10, 0, 1))));
        assertThrows(ProximityException.class,
                () -> L2capFraming.frame(new byte[L2capFraming.MAX_FRAME + 1]));
    }

    @Test
    public void aFrameOfExactlyOneMebibyteIsAccepted() throws Exception {
        byte[] body = new byte[L2capFraming.MAX_FRAME];
        assertNotNull(L2capFraming.readFrame(stream(L2capFraming.frame(body))));
    }

    @Test
    public void aHeaderWithTheTopBitSetIsRejected() {
        assertThrows(ProximityException.class,
                () -> L2capFraming.readFrame(stream(bytes(0x80, 0, 0, 0))));
    }

    @Test
    public void thePsmEncodesAsFourBigEndianBytes() {
        assertArrayEquals(bytes(0, 0, 0, 0xC0), L2capFraming.encodePsm(192));
        assertEquals(192, L2capFraming.decodePsm(bytes(0, 0, 0, 0xC0)));
        assertEquals(0xFFFF, L2capFraming.decodePsm(bytes(0, 0, 0xFF, 0xFF)));
    }

    @Test
    public void aPsmValueOfTheWrongLengthIsRejected() {
        assertThrows(ProximityException.class, () -> L2capFraming.decodePsm(bytes(0xC0)));
        assertThrows(ProximityException.class, () -> L2capFraming.decodePsm(bytes(0, 0xC0)));
        assertThrows(ProximityException.class,
                () -> L2capFraming.decodePsm(bytes(0, 0, 0, 0, 0xC0)));
    }

    @Test
    public void aPsmOutsideOneToSixtyFiveThousandIsRejected() {
        assertThrows(ProximityException.class, () -> L2capFraming.decodePsm(bytes(0, 0, 0, 0)));
        assertThrows(ProximityException.class,
                () -> L2capFraming.decodePsm(bytes(0, 1, 0, 0)));
        assertThrows(ProximityException.class, () -> L2capFraming.encodePsm(0));
        assertThrows(ProximityException.class, () -> L2capFraming.encodePsm(0x10000));
    }
}
