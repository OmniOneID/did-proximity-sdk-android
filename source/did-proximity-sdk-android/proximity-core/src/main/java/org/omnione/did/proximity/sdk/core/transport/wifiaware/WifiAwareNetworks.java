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

import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.aware.Characteristics;
import android.net.wifi.aware.DiscoverySession;
import android.net.wifi.aware.PeerHandle;
import android.net.wifi.aware.WifiAwareDataPathSecurityConfig;
import android.net.wifi.aware.WifiAwareNetworkSpecifier;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

import java.nio.charset.StandardCharsets;

@RequiresApi(Build.VERSION_CODES.Q)
final class WifiAwareNetworks {

    static final byte[] HELLO_PUB = "helloPub".getBytes(StandardCharsets.UTF_8);
    static final byte[] HELLO_SUB = "helloSub".getBytes(StandardCharsets.UTF_8);
    static final String IO_THREAD = "proximity-wifi-aware";

    private WifiAwareNetworks() {}

    @NonNull
    static NetworkRequest request(@NonNull DiscoverySession session,
                                  @NonNull PeerHandle peer,
                                  @NonNull String passphrase,
                                  int port) {
        WifiAwareNetworkSpecifier.Builder specifier =
                new WifiAwareNetworkSpecifier.Builder(session, peer);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            specifier.setDataPathSecurityConfig(new WifiAwareDataPathSecurityConfig.Builder(
                    Characteristics.WIFI_AWARE_CIPHER_SUITE_NCS_SK_128)
                    .setPskPassphrase(passphrase)
                    .build());
        } else {
            specifier.setPskPassphrase(passphrase);
        }
        if (port > 0) {
            specifier.setPort(port);
        }
        return new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI_AWARE)
                .setNetworkSpecifier(specifier.build())
                .build();
    }
}
