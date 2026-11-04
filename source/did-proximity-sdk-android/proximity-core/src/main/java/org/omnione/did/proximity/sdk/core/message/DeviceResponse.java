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

import java.util.Collections;
import java.util.List;

public final class DeviceResponse {

    @NonNull public final String version;
    @NonNull public final List<Document> documents;
    public final int status;

    DeviceResponse(@NonNull String version, @NonNull List<Document> documents, int status) {
        this.version = version;
        this.documents = Collections.unmodifiableList(documents);
        this.status = status;
    }
}
