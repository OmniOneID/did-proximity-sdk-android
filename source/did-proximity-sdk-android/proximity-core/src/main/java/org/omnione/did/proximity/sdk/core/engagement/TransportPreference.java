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

package org.omnione.did.proximity.sdk.core.engagement;

import androidx.annotation.NonNull;

import org.omnione.did.proximity.sdk.core.ProximityException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class TransportPreference {

    private final List<TransportMode> order;

    private TransportPreference(@NonNull List<TransportMode> order) {
        this.order = Collections.unmodifiableList(order);
    }

    @NonNull
    public static TransportPreference of(@NonNull TransportMode... order) {
        return of(Arrays.asList(order));
    }

    @NonNull
    public static TransportPreference of(@NonNull List<TransportMode> order) {
        List<TransportMode> distinct = new ArrayList<>();
        for (TransportMode mode : order) {
            if (!distinct.contains(mode)) {
                distinct.add(mode);
            }
        }
        if (distinct.isEmpty()) {
            throw new ProximityException("a transport preference needs at least one mode");
        }
        return new TransportPreference(distinct);
    }

    @NonNull
    public static TransportPreference from(@NonNull ModePreference preference) {
        List<TransportMode> order = new ArrayList<>();
        for (BleMode mode : preference.order()) {
            order.add(TransportMode.of(mode));
        }
        return of(order);
    }

    @NonNull
    public List<TransportMode> order() {
        return order;
    }

    @NonNull
    public TransportPreference without(@NonNull TransportMode mode) {
        List<TransportMode> remaining = new ArrayList<>(order);
        remaining.remove(mode);
        return of(remaining);
    }
}
