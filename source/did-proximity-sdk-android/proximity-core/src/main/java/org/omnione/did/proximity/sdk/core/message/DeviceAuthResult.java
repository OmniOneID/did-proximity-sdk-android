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

package org.omnione.did.proximity.sdk.core.message;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public final class DeviceAuthResult {

    public enum Outcome {
        VERIFIED,
        INVALID,
        ABSENT
    }

    public enum Method {
        SIGNATURE,
        MAC,
        NONE
    }

    @NonNull public final Outcome outcome;
    @NonNull public final Method method;
    @Nullable public final String detail;

    DeviceAuthResult(@NonNull Outcome outcome,
                     @NonNull Method method,
                     @Nullable String detail) {
        this.outcome = outcome;
        this.method = method;
        this.detail = detail;
    }

    public boolean verified() {
        return outcome == Outcome.VERIFIED;
    }

    @NonNull
    static DeviceAuthResult passed(@NonNull Method method) {
        return new DeviceAuthResult(Outcome.VERIFIED, method, null);
    }

    @NonNull
    static DeviceAuthResult failed(@NonNull Method method, @NonNull String detail) {
        return new DeviceAuthResult(Outcome.INVALID, method, detail);
    }

    @NonNull
    static DeviceAuthResult absent(@NonNull String detail) {
        return new DeviceAuthResult(Outcome.ABSENT, Method.NONE, detail);
    }
}
