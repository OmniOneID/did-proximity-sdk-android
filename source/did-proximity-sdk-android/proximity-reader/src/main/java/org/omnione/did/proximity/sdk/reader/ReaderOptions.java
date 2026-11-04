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

import androidx.annotation.NonNull;

public final class ReaderOptions {

    private final boolean l2cap;

    private ReaderOptions(boolean l2cap) {
        this.l2cap = l2cap;
    }

    @NonNull
    public static ReaderOptions defaults() {
        return new ReaderOptions(false);
    }

    @NonNull
    public ReaderOptions withL2cap(boolean enabled) {
        return new ReaderOptions(enabled);
    }

    public boolean l2cap() {
        return l2cap;
    }
}
