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

import android.app.Activity;
import android.content.ComponentName;
import android.content.pm.PackageManager;
import android.nfc.NfcAdapter;
import android.nfc.cardemulation.CardEmulation;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.omnione.did.proximity.sdk.core.logger.ProximityLogger;
import org.omnione.did.proximity.sdk.holder.NfcEngagementRegistry;

public final class NfcEngagementPreference {

    interface Routing {
        boolean available();

        boolean foregroundPreferenceAllowed();

        boolean setPreferred(@NonNull ComponentName service);

        void unsetPreferred();
    }

    private NfcEngagementPreference() {
    }

    public static boolean prefer(@NonNull Activity activity) {
        return prefer(activity, new ComponentName(activity, MdocHostApduService.class));
    }

    public static boolean prefer(@NonNull Activity activity, @NonNull ComponentName service) {
        return prefer(routingFor(activity), service);
    }

    public static void release(@NonNull Activity activity) {
        release(routingFor(activity), NfcEngagementRegistry.isNfcDataSessionLive());
    }

    static boolean prefer(@Nullable Routing routing, @NonNull ComponentName service) {
        if (routing == null || !routing.available()) {
            ProximityLogger.getInstance().d("nfc preference: no usable adapter, staying out of"
                    + " the way of the platform routing");
            return false;
        }
        if (!routing.foregroundPreferenceAllowed()) {
            ProximityLogger.getInstance().d("nfc preference: the platform refuses a foreground"
                    + " preference for category other");
            return false;
        }
        boolean applied = routing.setPreferred(service);
        ProximityLogger.getInstance().d(applied
                ? "nfc preference taken while this activity is resumed"
                : "nfc preference was not accepted by the platform");
        return applied;
    }

    static void release(@Nullable Routing routing) {
        release(routing, false);
    }

    static void release(@Nullable Routing routing, boolean nfcDataSessionLive) {
        if (routing == null || !routing.available()) {
            return;
        }
        if (nfcDataSessionLive) {
            ProximityLogger.getInstance().d("nfc preference kept: an nfc data session is live");
            return;
        }
        routing.unsetPreferred();
        ProximityLogger.getInstance().d("nfc preference released");
    }

    @Nullable
    private static Routing routingFor(@NonNull Activity activity) {
        NfcAdapter adapter = NfcAdapter.getDefaultAdapter(activity);
        if (adapter == null
                || !activity.getPackageManager()
                        .hasSystemFeature(PackageManager.FEATURE_NFC_HOST_CARD_EMULATION)) {
            return null;
        }
        CardEmulation emulation;
        try {
            emulation = CardEmulation.getInstance(adapter);
        } catch (RuntimeException unavailable) {
            return null;
        }
        if (emulation == null) {
            return null;
        }
        return new PlatformRouting(activity, adapter, emulation);
    }

    private static final class PlatformRouting implements Routing {
        private final Activity activity;
        private final NfcAdapter adapter;
        private final CardEmulation emulation;

        PlatformRouting(@NonNull Activity activity,
                        @NonNull NfcAdapter adapter,
                        @NonNull CardEmulation emulation) {
            this.activity = activity;
            this.adapter = adapter;
            this.emulation = emulation;
        }

        @Override
        public boolean available() {
            return adapter.isEnabled();
        }

        @Override
        public boolean foregroundPreferenceAllowed() {
            return emulation.categoryAllowsForegroundPreference(CardEmulation.CATEGORY_OTHER);
        }

        @Override
        public boolean setPreferred(@NonNull ComponentName service) {
            try {
                ProximityLogger.getInstance().d("nfc preference for " + service.getClassName());
                return emulation.setPreferredService(activity, service);
            } catch (RuntimeException rejected) {
                ProximityLogger.getInstance().e("nfc preference rejected: " + rejected);
                return false;
            }
        }

        @Override
        public void unsetPreferred() {
            try {
                emulation.unsetPreferredService(activity);
            } catch (RuntimeException e) {
                ProximityLogger.getInstance().d("unset preferred service failed: " + e);
            }
        }
    }
}
