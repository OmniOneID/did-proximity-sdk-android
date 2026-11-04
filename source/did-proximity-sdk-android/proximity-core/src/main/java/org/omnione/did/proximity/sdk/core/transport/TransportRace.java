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

package org.omnione.did.proximity.sdk.core.transport;

import androidx.annotation.NonNull;

import org.omnione.did.proximity.sdk.core.ProximityException;
import org.omnione.did.proximity.sdk.core.engagement.TransportMode;
import org.omnione.did.proximity.sdk.core.logger.ProximityLogger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class TransportRace {

    public static final class Leg {

        private final TransportMode mode;
        private final Transport transport;

        private Leg(@NonNull TransportMode mode, @NonNull Transport transport) {
            this.mode = mode;
            this.transport = transport;
        }

        @NonNull
        public static Leg of(@NonNull TransportMode mode, @NonNull Transport transport) {
            return new Leg(mode, transport);
        }
    }

    private static final Object FAILED = new Object();
    private static final Object STOPPED = new Object();

    private final List<Leg> legs;
    private final AtomicReference<Object> outcome = new AtomicReference<>();
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final Object failureLock = new Object();
    private final Set<Leg> failed = new HashSet<>();

    private ProximityException lastFailure;
    private volatile Transport.Listener host;

    private TransportRace(@NonNull List<Leg> legs) {
        this.legs = Collections.unmodifiableList(new ArrayList<>(legs));
    }

    @NonNull
    public static TransportRace of(@NonNull List<Leg> legs) {
        if (legs.isEmpty()) {
            throw new ProximityException("a transport race needs at least one leg");
        }
        return new TransportRace(legs);
    }

    public void start(@NonNull Transport.Listener listener) {
        if (!started.compareAndSet(false, true)) {
            throw new ProximityException("transport race already started");
        }
        if (outcome.get() == STOPPED) {
            stopEveryLeg();
            return;
        }
        this.host = listener;
        for (Leg leg : legs) {
            try {
                leg.transport.start(legListener(leg));
            } catch (RuntimeException e) {
                ProximityLogger.getInstance().e(
                        "transport race leg " + leg.mode + " failed to open: " + e);
                recordFailure(leg, failureOf(leg, e));
            }
        }
        Object settled = outcome.get();
        if (settled == STOPPED) {
            stopEveryLeg();
            return;
        }
        if (settled instanceof Leg) {
            for (Leg leg : legs) {
                if (leg != settled) {
                    stopQuietly(leg);
                }
            }
        }
    }

    public void send(@NonNull byte[] message) {
        Object active = outcome.get();
        if (!(active instanceof Leg)) {
            throw new ProximityException("no transport has connected yet");
        }
        ((Leg) active).transport.send(message);
    }

    public void stop() {
        outcome.set(STOPPED);
        stopEveryLeg();
    }

    @NonNull
    private Transport.Listener legListener(@NonNull Leg leg) {
        return new Transport.Listener() {
            @Override
            public void onConnected() {
                if (!claim(leg)) {
                    return;
                }
                host.onConnected();
            }

            @Override
            public void onMessage(@NonNull byte[] message) {
                if (!claim(leg)) {
                    return;
                }
                host.onMessage(message);
            }

            @Override
            public void onError(@NonNull ProximityException error) {
                if (outcome.get() == leg) {
                    host.onError(error);
                    return;
                }
                recordFailure(leg, error);
            }

            @Override
            public void onDisconnected() {
                if (outcome.get() != leg) {
                    return;
                }
                host.onDisconnected();
            }
        };
    }

    private boolean claim(@NonNull Leg leg) {
        if (outcome.compareAndSet(null, leg)) {
            ProximityLogger.getInstance().d("transport race won by " + leg.mode);
            for (Leg other : legs) {
                if (other != leg) {
                    stopQuietly(other);
                }
            }
            return true;
        }
        return outcome.get() == leg;
    }

    private void recordFailure(@NonNull Leg leg, @NonNull ProximityException error) {
        ProximityException aggregate = null;
        synchronized (failureLock) {
            failed.add(leg);
            lastFailure = error;
            if (failed.size() == legs.size() && outcome.compareAndSet(null, FAILED)) {
                aggregate = lastFailure;
            }
        }
        if (aggregate != null) {
            host.onError(aggregate);
        }
    }

    private void stopEveryLeg() {
        for (Leg leg : legs) {
            stopQuietly(leg);
        }
    }

    @NonNull
    private static ProximityException failureOf(@NonNull Leg leg, @NonNull RuntimeException e) {
        if (e instanceof ProximityException) {
            return (ProximityException) e;
        }
        return new ProximityException(
                "transport race leg " + leg.mode + " failed to open", e);
    }

    private static void stopQuietly(@NonNull Leg leg) {
        try {
            leg.transport.stop();
        } catch (RuntimeException e) {
            ProximityLogger.getInstance().d("transport race leg stop failed: " + e);
        }
    }
}
