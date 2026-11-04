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

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.omnione.did.proximity.sdk.core.engagement.NfcOptions;
import org.omnione.did.proximity.sdk.core.transport.nfc.NfcHolderTransport;

import java.lang.ref.WeakReference;
import java.util.concurrent.atomic.AtomicReference;

public final class NfcEngagementRegistry {

    private static final AtomicReference<WeakReference<HolderSession>> LIVE =
            new AtomicReference<>();

    private NfcEngagementRegistry() {
    }

    static void arm(@NonNull HolderSession session) {
        LIVE.set(new WeakReference<>(session));
    }

    static void disarm(@NonNull HolderSession session) {
        WeakReference<HolderSession> current = LIVE.get();
        if (current != null && current.get() == session) {
            LIVE.compareAndSet(current, null);
        }
    }

    @Nullable
    public static HolderSession liveSession() {
        WeakReference<HolderSession> current = LIVE.get();
        return current == null ? null : current.get();
    }

    @NonNull
    public static byte[] nfcEngagementBytes(@NonNull HolderSession session) {
        return session.nfcEngagementBytes();
    }

    @NonNull
    public static java.util.Map<org.omnione.did.proximity.sdk.core.engagement.BleMode,
            java.util.UUID> serviceUuids(@NonNull HolderSession session) {
        return session.serviceUuids();
    }

    @Nullable
    public static Integer l2capPsm(@NonNull HolderSession session) {
        return session.l2capPsm();
    }

    @Nullable
    public static NfcOptions nfcOptions(@NonNull HolderSession session) {
        return session.nfcOptions();
    }

    @Nullable
    public static NfcHolderTransport nfcTransport(@NonNull HolderSession session) {
        return session.nfcTransport();
    }

    public static boolean isNfcDataSessionLive() {
        HolderSession session = liveSession();
        if (session == null) {
            return false;
        }
        NfcHolderTransport transport = session.nfcTransport();
        return transport != null && transport.isLinkActive();
    }

    public static void latch(@NonNull HolderSession session,
                             @NonNull byte[] handoverSelect,
                             @Nullable byte[] handoverRequest) {
        session.onNfcHandoverServed(handoverSelect, handoverRequest);
    }
}
