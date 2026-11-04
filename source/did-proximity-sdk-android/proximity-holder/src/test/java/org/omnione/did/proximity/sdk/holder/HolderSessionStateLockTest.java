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

package org.omnione.did.proximity.sdk.holder;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import androidx.annotation.NonNull;

import com.upokecenter.cbor.CBORObject;

import org.junit.Test;
import org.omnione.did.proximity.sdk.core.ProximityException;
import org.omnione.did.proximity.sdk.core.cbor.CborTags;
import org.omnione.did.proximity.sdk.core.cbor.CoseKeys;
import org.omnione.did.proximity.sdk.core.engagement.BleMode;
import org.omnione.did.proximity.sdk.core.engagement.EngagementCodec;
import org.omnione.did.proximity.sdk.core.engagement.SessionTranscripts;
import org.omnione.did.proximity.sdk.core.session.SessionCrypto;
import org.omnione.did.proximity.sdk.core.session.SessionKeys;
import org.omnione.did.proximity.sdk.core.transport.Transport;
import org.omnione.did.proximity.sdk.core.util.NoDeadline;

import java.lang.reflect.Field;
import java.security.KeyPair;
import java.security.interfaces.ECPublicKey;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class HolderSessionStateLockTest {

    private static final String MDL = "org.iso.18013.5.1.mDL";
    private static final String NS = "org.iso.18013.5.1";

    private static final class FakeTransport implements Transport {
        final List<byte[]> sent = new ArrayList<>();
        Listener listener;
        boolean stopped;

        @Override
        public void start(@NonNull Listener listener) {
            this.listener = listener;
        }

        @Override
        public void send(@NonNull byte[] message) {
            sent.add(message);
        }

        @Override
        public void stop() {
            stopped = true;
        }
    }

    private static final class RecordingListener implements HolderSession.Listener {
        ProximityRequest request;
        boolean terminated;
        ProximityException error;

        @Override public void onReaderConnected() { }
        @Override public void onRequest(@NonNull ProximityRequest r) { request = r; }
        @Override public void onResponseSent() { }
        @Override public void onError(@NonNull ProximityException e) { error = e; }
        @Override public void onTerminated() { terminated = true; }
    }

    private static final class EstablishedHolder {
        final FakeTransport transport = new FakeTransport();
        final RecordingListener listener = new RecordingListener();
        final HolderSession session = HolderSession.create(
                transport, new NoDeadline(), BleMode.PERIPHERAL_SERVER);
        final byte[] transcript;

        EstablishedHolder() {
            String qr = session.deviceEngagementQr();
            session.start(listener);

            byte[] engagement = EngagementCodec.decode(qr);
            KeyPair readerPair = CoseKeys.generateEphemeral();
            byte[] readerKeyBytes = CoseKeys
                    .toCoseKey((ECPublicKey) readerPair.getPublic()).EncodeToBytes();
            this.transcript = SessionTranscripts.forQr(engagement, readerKeyBytes);
            ECPublicKey eDeviceKey = CoseKeys.toPublicKey(CBORObject.DecodeFromBytes(
                    CborTags.untag(CBORObject.DecodeFromBytes(engagement)
                            .get(CBORObject.FromObject(1)).get(1))));
            SessionCrypto readerCrypto = SessionCrypto.forReader(
                    SessionKeys.derive(readerPair.getPrivate(), eDeviceKey, transcript));

            CBORObject establishment = CBORObject.NewMap();
            establishment.Add("eReaderKey", CborTags.tagged(readerKeyBytes));
            establishment.Add("data", readerCrypto.encrypt(deviceRequestCbor()));

            transport.listener.onConnected();
            transport.listener.onMessage(establishment.EncodeToBytes());
        }
    }

    private static byte[] deviceRequestCbor() {
        CBORObject elements = CBORObject.NewMap();
        elements.Add("family_name", false);

        CBORObject nameSpaces = CBORObject.NewMap();
        nameSpaces.Add(NS, elements);

        CBORObject itemsRequest = CBORObject.NewMap();
        itemsRequest.Add("docType", MDL);
        itemsRequest.Add("nameSpaces", nameSpaces);

        CBORObject docRequest = CBORObject.NewMap();
        docRequest.Add("itemsRequest", CborTags.tagged(itemsRequest.EncodeToBytes()));

        CBORObject docRequests = CBORObject.NewArray();
        docRequests.Add(docRequest);

        CBORObject request = CBORObject.NewMap();
        request.Add("version", "1.0");
        request.Add("docRequests", docRequests);
        return request.EncodeToBytes();
    }

    @Test
    public void closeDestroysTheSessionKeys() throws Exception {
        EstablishedHolder session = new EstablishedHolder();
        Object crypto = fieldValue(session.session, "crypto");
        assertNotNull(crypto);
        assertFalse(isAllZero((byte[]) fieldValue(crypto, "encryptKey")));
        assertFalse(isAllZero((byte[]) fieldValue(crypto, "decryptKey")));

        session.session.closeWithoutNotifying();

        assertTrue(isAllZero((byte[]) fieldValue(crypto, "encryptKey")));
        assertTrue(isAllZero((byte[]) fieldValue(crypto, "decryptKey")));
        assertNull(fieldValue(session.session, "crypto"));
    }

    @Test
    public void closeZeroesTheRetainedSessionTranscript() throws Exception {
        EstablishedHolder session = new EstablishedHolder();
        byte[] retained = (byte[]) fieldValue(session.session, "sessionTranscript");
        assertNotNull(retained);
        assertFalse(isAllZero(retained));

        session.session.closeWithoutNotifying();

        assertTrue(isAllZero(retained));
        assertNull(fieldValue(session.session, "sessionTranscript"));
    }

    @Test
    public void closeDropsTheEphemeralDeviceKeyPair() throws Exception {
        EstablishedHolder session = new EstablishedHolder();
        assertNotNull(fieldValue(session.session, "eDeviceKeyPair"));

        session.session.closeWithoutNotifying();

        assertNull(fieldValue(session.session, "eDeviceKeyPair"));
    }

    @Test
    public void terminateDestroysTheSessionKeys() throws Exception {
        EstablishedHolder session = new EstablishedHolder();
        Object crypto = fieldValue(session.session, "crypto");
        assertNotNull(crypto);

        session.session.terminate();

        assertTrue(isAllZero((byte[]) fieldValue(crypto, "encryptKey")));
        assertTrue(isAllZero((byte[]) fieldValue(crypto, "decryptKey")));
        assertTrue(session.listener.terminated);
    }

    @Test
    public void theDeliveredTranscriptSurvivesTheSessionEnding() {
        EstablishedHolder session = new EstablishedHolder();
        assertNotNull(session.listener.request);
        byte[] delivered = session.listener.request.sessionTranscript;
        assertArrayEquals(SessionTranscripts.taggedBytes(session.transcript), delivered);

        session.session.closeWithoutNotifying();

        assertArrayEquals(SessionTranscripts.taggedBytes(session.transcript), delivered);
    }

    @Test
    public void closeBlocksUntilAnInFlightCriticalSectionReleasesTheLock() throws Exception {
        EstablishedHolder session = new EstablishedHolder();
        Object stateLock = fieldValue(session.session, "stateLock");
        Object crypto = fieldValue(session.session, "crypto");
        assertNotNull(crypto);
        assertFalse(isAllZero((byte[]) fieldValue(crypto, "encryptKey")));

        CountDownLatch holderInside = new CountDownLatch(1);
        CountDownLatch releaseHolder = new CountDownLatch(1);
        Thread criticalSectionHolder = new Thread(() -> {
            synchronized (stateLock) {
                holderInside.countDown();
                try {
                    releaseHolder.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        criticalSectionHolder.start();
        assertTrue(holderInside.await(2, TimeUnit.SECONDS));

        Thread closer = new Thread(session.session::closeWithoutNotifying);
        closer.start();

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (closer.getState() != Thread.State.BLOCKED) {
            if (System.nanoTime() > deadline) {
                fail("close() never blocked waiting for stateLock");
            }
            Thread.sleep(5);
        }

        assertFalse(isAllZero((byte[]) fieldValue(crypto, "encryptKey")));

        releaseHolder.countDown();
        criticalSectionHolder.join(2000);
        closer.join(2000);

        assertTrue(isAllZero((byte[]) fieldValue(crypto, "encryptKey")));
        assertTrue(isAllZero((byte[]) fieldValue(crypto, "decryptKey")));
    }

    @Test
    public void anInFlightEstablishmentBlocksCloseUntilItCommits() throws Exception {
        FakeTransport transport = new FakeTransport();
        HolderSession session = HolderSession.create(
                transport, new NoDeadline(), BleMode.PERIPHERAL_SERVER);
        String qr = session.deviceEngagementQr();
        RecordingListener listener = new RecordingListener();
        session.start(listener);
        Object stateLock = fieldValue(session, "stateLock");

        CountDownLatch holderInside = new CountDownLatch(1);
        CountDownLatch releaseHolder = new CountDownLatch(1);
        Thread criticalSectionHolder = new Thread(() -> {
            synchronized (stateLock) {
                holderInside.countDown();
                try {
                    releaseHolder.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        criticalSectionHolder.start();
        assertTrue(holderInside.await(2, TimeUnit.SECONDS));

        Thread closer = new Thread(session::closeWithoutNotifying);
        closer.start();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (closer.getState() != Thread.State.BLOCKED) {
            if (System.nanoTime() > deadline) {
                fail("close() never blocked waiting for stateLock");
            }
            Thread.sleep(5);
        }
        assertNull(fieldValue(session, "crypto"));

        releaseHolder.countDown();
        criticalSectionHolder.join(2000);
        closer.join(2000);

        assertNull(fieldValue(session, "crypto"));
        assertNull(fieldValue(session, "eDeviceKeyPair"));
        assertNull(listener.request);
    }

    private static boolean isAllZero(@NonNull byte[] value) {
        for (byte b : value) {
            if (b != 0) {
                return false;
            }
        }
        return true;
    }

    private static Object fieldValue(@NonNull Object target, @NonNull String name)
            throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }
}
