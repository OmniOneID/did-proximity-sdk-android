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

package org.omnione.did.proximity.sdk.reader.nfc;

import android.app.Activity;
import android.content.Context;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.tech.IsoDep;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.omnione.did.proximity.sdk.core.ProximityException;
import org.omnione.did.proximity.sdk.core.engagement.ModePreference;
import org.omnione.did.proximity.sdk.core.engagement.TransportPreference;
import org.omnione.did.proximity.sdk.core.logger.ProximityLogger;
import org.omnione.did.proximity.sdk.core.nfc.HandoverRequester;
import org.omnione.did.proximity.sdk.core.nfc.NfcHandoverResult;
import org.omnione.did.proximity.sdk.core.nfc.NfcHandoverResults;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

public final class NfcEngagementReader implements AutoCloseable {

    public interface Listener {
        void onHandover(@NonNull NfcHandoverResult result);

        void onError(@NonNull ProximityException error);
    }

    static final int READER_FLAGS = NfcAdapter.FLAG_READER_NFC_A
            | NfcAdapter.FLAG_READER_NFC_B
            | NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK
            | NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS;

    private static final int TAG_TIMEOUT_MS = 20_000;

    private final Activity activity;
    private final NfcAdapter adapter;
    private final HandoverRequester requester;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean handled = new AtomicBoolean();

    private volatile Listener listener;

    private NfcEngagementReader(@NonNull Activity activity,
                                @NonNull NfcAdapter adapter,
                                @NonNull HandoverRequester requester) {
        this.activity = activity;
        this.adapter = adapter;
        this.requester = requester;
    }

    @NonNull
    public static NfcEngagementReader create(@NonNull Activity activity,
                                             @NonNull ModePreference preference) {
        return create(activity, TransportPreference.from(preference));
    }

    @NonNull
    public static NfcEngagementReader create(@NonNull Activity activity,
                                             @NonNull TransportPreference preference) {
        NfcAdapter adapter = NfcAdapter.getDefaultAdapter(activity);
        if (adapter == null) {
            throw new ProximityException("this device has no NFC adapter");
        }
        return new NfcEngagementReader(activity, adapter,
                HandoverRequester.create(UUID.randomUUID(), preference));
    }

    public static boolean isSupported(@NonNull Context context) {
        return NfcAdapter.getDefaultAdapter(context) != null;
    }

    public static boolean isEnabled(@NonNull Context context) {
        NfcAdapter adapter = NfcAdapter.getDefaultAdapter(context);
        return adapter != null && adapter.isEnabled();
    }

    public void start(@NonNull Listener listener) {
        this.listener = listener;
        handled.set(false);
        adapter.enableReaderMode(activity, this::onTagDiscovered, READER_FLAGS, null);
        ProximityLogger.getInstance().d("nfc reader mode enabled");
    }

    public void stop() {
        listener = null;
        try {
            adapter.disableReaderMode(activity);
        } catch (RuntimeException e) {
            ProximityLogger.getInstance().d("disable reader mode failed: " + e);
        }
        ProximityLogger.getInstance().d("nfc reader mode disabled");
    }

    @Override
    public void close() {
        stop();
    }

    private void onTagDiscovered(@Nullable Tag tag) {
        if (tag == null || !handled.compareAndSet(false, true)) {
            return;
        }
        IsoDep isoDep = IsoDep.get(tag);
        if (isoDep == null) {
            handled.set(false);
            deliverError(new ProximityException("the tapped tag does not speak ISO-DEP"));
            return;
        }
        NfcHandoverResult result = null;
        try {
            isoDep.connect();
            isoDep.setTimeout(TAG_TIMEOUT_MS);
            result = requester.engage(new IsoDepApduChannel(isoDep));
            deliverResult(result);
        } catch (IOException e) {
            handled.set(false);
            deliverError(new ProximityException("NFC link lost during the handover", e));
        } catch (ProximityException e) {
            handled.set(false);
            deliverError(e);
        } finally {
            if (result == null || result.channel() == null) {
                closeQuietly(isoDep);
            } else {
                ProximityLogger.getInstance().d("nfc link kept open for data retrieval");
            }
        }
    }

    private void deliverResult(@NonNull NfcHandoverResult result) {
        main.post(() -> {
            Listener host = listener;
            if (host != null) {
                host.onHandover(result);
            } else {
                NfcHandoverResults.closeQuietly(result);
            }
        });
    }

    private void deliverError(@NonNull ProximityException error) {
        ProximityLogger.getInstance().e("nfc handover failed: " + error);
        main.post(() -> {
            Listener host = listener;
            if (host != null) {
                host.onError(error);
            }
        });
    }

    private static void closeQuietly(@NonNull IsoDep isoDep) {
        try {
            isoDep.close();
        } catch (IOException ignoredWhileClosing) {
        }
    }
}
