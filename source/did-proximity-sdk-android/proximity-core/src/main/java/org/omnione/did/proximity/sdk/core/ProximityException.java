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

package org.omnione.did.proximity.sdk.core;

public class ProximityException extends RuntimeException {

    public enum Reason {
        ENGAGEMENT_TIMEOUT,
        RESPONSE_TIMEOUT,
        OTHER
    }

    private final Reason reason;

    public ProximityException(String message) {
        this(message, Reason.OTHER, null);
    }

    public ProximityException(String message, Throwable cause) {
        this(message, Reason.OTHER, cause);
    }

    public ProximityException(String message, Reason reason) {
        this(message, reason, null);
    }

    public ProximityException(String message, Reason reason, Throwable cause) {
        super(message, cause);
        this.reason = reason == null ? Reason.OTHER : reason;
    }

    public Reason reason() {
        return reason;
    }
}
