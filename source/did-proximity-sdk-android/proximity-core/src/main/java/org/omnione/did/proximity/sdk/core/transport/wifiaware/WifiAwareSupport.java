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

package org.omnione.did.proximity.sdk.core.transport.wifiaware;

import android.content.Context;
import android.content.pm.PackageManager;
import android.net.wifi.WifiManager;
import android.net.wifi.aware.Characteristics;
import android.net.wifi.aware.WifiAwareManager;
import android.os.Build;

import androidx.annotation.NonNull;

public final class WifiAwareSupport {

    public static final int MIN_SDK = Build.VERSION_CODES.Q;

    static final byte BAND_2_4_GHZ = 0x04;
    static final byte BAND_5_GHZ = 0x10;

    private WifiAwareSupport() {}

    @SuppressWarnings("MissingPermission")
    public static boolean isAvailable(@NonNull Context context) {
        if (Build.VERSION.SDK_INT < MIN_SDK) {
            return false;
        }
        Context app = context.getApplicationContext();
        if (!app.getPackageManager().hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE)) {
            return false;
        }
        WifiAwareManager manager = app.getSystemService(WifiAwareManager.class);
        if (manager == null || !manager.isAvailable()) {
            return false;
        }
        return supportsNcsSk128(manager);
    }

    @SuppressWarnings("MissingPermission")
    private static boolean supportsNcsSk128(@NonNull WifiAwareManager manager) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return true;
        }
        Characteristics characteristics = manager.getCharacteristics();
        if (characteristics == null) {
            return true;
        }
        int suites = characteristics.getSupportedCipherSuites();
        return (suites & Characteristics.WIFI_AWARE_CIPHER_SUITE_NCS_SK_128) != 0;
    }

    @NonNull
    public static byte[] supportedBands(@NonNull Context context) {
        byte bands = BAND_2_4_GHZ;
        WifiManager wifi = context.getApplicationContext().getSystemService(WifiManager.class);
        if (wifi != null && wifi.is5GHzBandSupported()) {
            bands |= BAND_5_GHZ;
        }
        return new byte[]{bands};
    }
}
