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
import android.nfc.NfcAdapter;
import android.nfc.Tag;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.omnione.did.proximity.sdk.core.logger.ProximityLogger;

public final class NfcTagDispatchGuard {

    private final Activity activity;
    @Nullable private final NfcAdapter adapter;

    private boolean engaged;

    private NfcTagDispatchGuard(@NonNull Activity activity, @Nullable NfcAdapter adapter) {
        this.activity = activity;
        this.adapter = adapter;
    }

    @NonNull
    public static NfcTagDispatchGuard create(@NonNull Activity activity) {
        return new NfcTagDispatchGuard(activity, NfcAdapter.getDefaultAdapter(activity));
    }

    public void engage() {
        if (adapter == null || engaged) {
            return;
        }
        engaged = true;
        adapter.enableReaderMode(activity, NfcTagDispatchGuard::ignore,
                NfcEngagementReader.READER_FLAGS, null);
        ProximityLogger.getInstance().d("nfc tag dispatch suppressed");
    }

    public void release() {
        if (adapter == null || !engaged) {
            return;
        }
        engaged = false;
        try {
            adapter.disableReaderMode(activity);
        } catch (RuntimeException e) {
            ProximityLogger.getInstance().d("disable reader mode failed: " + e);
        }
        ProximityLogger.getInstance().d("nfc tag dispatch released");
    }

    private static void ignore(@Nullable Tag tag) {
    }
}
