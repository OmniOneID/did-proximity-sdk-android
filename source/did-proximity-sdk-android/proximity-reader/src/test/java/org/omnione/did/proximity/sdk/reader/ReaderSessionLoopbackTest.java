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
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import androidx.annotation.NonNull;

import com.upokecenter.cbor.CBORObject;

import org.junit.Test;
import org.omnione.did.proximity.sdk.core.ProximityException;
import org.omnione.did.proximity.sdk.core.cbor.CborTags;
import org.omnione.did.proximity.sdk.core.engagement.BleMode;
import org.omnione.did.proximity.sdk.core.message.ItemsRequest;
import org.omnione.did.proximity.sdk.core.transport.Transport;
import org.omnione.did.proximity.sdk.core.util.NoDeadline;
import org.omnione.did.proximity.sdk.holder.HolderSession;
import org.omnione.did.proximity.sdk.holder.ProximityRequest;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ReaderSessionLoopbackTest {

    private static final String DOC_TYPE = "eu.europa.ec.eudi.pid.1";

    private static final class Wire implements Transport {
        private Listener listener;
        private Wire peer;

        @Override
        public void start(@NonNull Listener listener) {
            this.listener = listener;
        }

        @Override
        public void send(@NonNull byte[] message) {
            peer.listener.onMessage(message);
        }

        @Override
        public void stop() {
        }

        void connect() {
            listener.onConnected();
        }
    }

    private static ItemsRequest request() {
        Map<String, Boolean> elements = new LinkedHashMap<>();
        elements.put("family_name", false);
        Map<String, Map<String, Boolean>> nameSpaces = new LinkedHashMap<>();
        nameSpaces.put(DOC_TYPE, elements);
        return ItemsRequest.of(DOC_TYPE, nameSpaces);
    }

    @Test
    public void aRequestAndResponseRoundTripBetweenHolderAndReader() {
        Wire holderWire = new Wire();
        Wire readerWire = new Wire();
        holderWire.peer = readerWire;
        readerWire.peer = holderWire;

        HolderSession holder = HolderSession.create(
                holderWire, new NoDeadline(), BleMode.PERIPHERAL_SERVER);
        List<ProximityRequest> received = new ArrayList<>();
        holder.start(new HolderSession.Listener() {
            @Override public void onReaderConnected() {}
            @Override public void onRequest(@NonNull ProximityRequest request) {
                received.add(request);
                holder.sendResponse(DeviceResponseFixture.of(DOC_TYPE, "family_name", "Kim"));
            }
            @Override public void onResponseSent() {}
            @Override public void onError(@NonNull ProximityException error) {
                throw error;
            }
            @Override public void onTerminated() {}
        });

        List<ProximityResponse> responses = new ArrayList<>();
        ReaderSession reader = ReaderSession.create(readerWire, new NoDeadline());
        reader.start(holder.deviceEngagementQr(), request(), new ReaderSession.Listener() {
            @Override public void onMdocConnected() {}
            @Override public void onResponse(@NonNull ProximityResponse response) {
                responses.add(response);
            }
            @Override public void onError(@NonNull ProximityException error) {
                throw error;
            }
            @Override public void onTerminated() {}
        });
        readerWire.connect();

        assertEquals(1, received.size());
        assertEquals(DOC_TYPE, received.get(0).docType);
        assertTrue(received.get(0).requestedElements.get(DOC_TYPE).containsKey("family_name"));

        assertEquals(1, responses.size());
        assertEquals("Kim", responses.get(0).documents.get(0).flatClaims().get("family_name"));
        assertEquals(0, responses.get(0).documentStatus);
        assertTrue(responses.get(0).deviceResponse.length > 0);
        assertTrue(responses.get(0).sessionTranscript.length > 0);
        assertArrayEquals(
                CborTags.untag(CBORObject.DecodeFromBytes(received.get(0).sessionTranscript)),
                responses.get(0).sessionTranscript);
        assertEquals(responses.get(0).documents.size(), responses.get(0).deviceAuth.size());
    }

    @Test
    public void terminateZeroesTheReaderSessionCryptoKeys() throws Exception {
        Wire holderWire = new Wire();
        Wire readerWire = new Wire();
        holderWire.peer = readerWire;
        readerWire.peer = holderWire;

        HolderSession holder = HolderSession.create(
                holderWire, new NoDeadline(), BleMode.PERIPHERAL_SERVER);
        holder.start(new HolderSession.Listener() {
            @Override public void onReaderConnected() {}
            @Override public void onRequest(@NonNull ProximityRequest request) {
                holder.sendResponse(DeviceResponseFixture.of(DOC_TYPE, "family_name", "Kim"));
            }
            @Override public void onResponseSent() {}
            @Override public void onError(@NonNull ProximityException error) {
                throw error;
            }
            @Override public void onTerminated() {}
        });

        ReaderSession reader = ReaderSession.create(readerWire, new NoDeadline());
        Object[] capturedCrypto = new Object[1];
        reader.start(holder.deviceEngagementQr(), request(), new ReaderSession.Listener() {
            @Override public void onMdocConnected() {}
            @Override public void onResponse(@NonNull ProximityResponse response) {
                capturedCrypto[0] = fieldValue(reader, "crypto");
            }
            @Override public void onError(@NonNull ProximityException error) {
                throw error;
            }
            @Override public void onTerminated() {}
        });
        readerWire.connect();

        assertNotNull(capturedCrypto[0]);
        assertArrayEquals(new byte[32], (byte[]) fieldValue(capturedCrypto[0], "encryptKey"));
        assertArrayEquals(new byte[32], (byte[]) fieldValue(capturedCrypto[0], "decryptKey"));
    }

    private static Object fieldValue(@NonNull Object target, @NonNull String name) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
}
