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

package org.omnione.did.proximity.sdk.holder;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.omnione.did.proximity.sdk.core.ProximityException;
import org.omnione.did.proximity.sdk.core.cbor.CoseKeys;
import org.omnione.did.proximity.sdk.core.engagement.BleMode;
import org.omnione.did.proximity.sdk.core.engagement.BleTransferMode;
import org.omnione.did.proximity.sdk.core.engagement.DeviceEngagement;
import org.omnione.did.proximity.sdk.core.engagement.DeviceRetrievalMethod;
import org.omnione.did.proximity.sdk.core.engagement.EngagementCodec;
import org.omnione.did.proximity.sdk.core.engagement.NfcOptions;
import org.omnione.did.proximity.sdk.core.engagement.SessionTranscripts;
import org.omnione.did.proximity.sdk.core.engagement.TransportMode;
import org.omnione.did.proximity.sdk.core.engagement.UnsupportedTransportException;
import org.omnione.did.proximity.sdk.core.engagement.WifiOptions;
import org.omnione.did.proximity.sdk.core.logger.ProximityLogger;
import org.omnione.did.proximity.sdk.core.message.DeviceRequestParser;
import org.omnione.did.proximity.sdk.core.message.ItemsRequest;
import org.omnione.did.proximity.sdk.core.session.SessionCrypto;
import org.omnione.did.proximity.sdk.core.session.SessionKeys;
import org.omnione.did.proximity.sdk.core.session.SessionMessages;
import org.omnione.did.proximity.sdk.core.transport.Transport;
import org.omnione.did.proximity.sdk.core.transport.TransportRace;
import org.omnione.did.proximity.sdk.core.transport.ble.BleIdent;
import org.omnione.did.proximity.sdk.core.transport.ble.BluetoothL2capServer;
import org.omnione.did.proximity.sdk.core.transport.ble.GattClientTransport;
import org.omnione.did.proximity.sdk.core.transport.ble.GattProfile;
import org.omnione.did.proximity.sdk.core.transport.ble.GattServerTransport;
import org.omnione.did.proximity.sdk.core.transport.ble.L2capServer;
import org.omnione.did.proximity.sdk.core.transport.ble.L2capSupport;
import org.omnione.did.proximity.sdk.core.transport.nfc.NfcHolderTransport;
import org.omnione.did.proximity.sdk.core.transport.wifiaware.WifiAwarePublisherTransport;
import org.omnione.did.proximity.sdk.core.transport.wifiaware.WifiAwareSupport;
import org.omnione.did.proximity.sdk.core.util.Deadline;
import org.omnione.did.proximity.sdk.core.util.HandlerDeadline;

import java.security.KeyPair;
import java.security.interfaces.ECPublicKey;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class HolderSession {

    public interface Listener {
        void onReaderConnected();

        void onRequest(@NonNull ProximityRequest request);

        void onResponseSent();

        void onError(@NonNull ProximityException error);

        void onTerminated();

        default void onEngagementDelivered(@NonNull EngagementMethod method) {
        }
    }

    public static final long ENGAGEMENT_TIMEOUT_MS = 90_000L;
    public static final long RESPONSE_TIMEOUT_MS = 60_000L;
    public static final int NFC_MAX_COMMAND_LENGTH = 0xFFFF;
    public static final int NFC_MAX_RESPONSE_LENGTH = 0x10000;

    interface TransportFactory {
        @NonNull
        TransportRace create(@NonNull byte[] eDeviceKeyBytes,
                             @NonNull List<DeviceRetrievalMethod> methods);
    }

    interface TransportBuilder {
        @NonNull
        Transport centralClient(@NonNull UUID serviceUuid, @NonNull byte[] expectedIdent);

        @NonNull
        Transport peripheralServer(@NonNull UUID serviceUuid);

        @NonNull
        default Transport peripheralServer(@NonNull UUID serviceUuid,
                                           @Nullable L2capServer l2cap) {
            if (l2cap != null) {
                throw new UnsupportedTransportException(
                        "this transport builder has no L2CAP capable peripheral server", null);
            }
            return peripheralServer(serviceUuid);
        }

        @NonNull
        default Transport wifiAware(@NonNull byte[] eDeviceKeyBytes,
                                    @NonNull WifiOptions options) {
            throw new UnsupportedTransportException(
                    "this transport builder has no Wi-Fi Aware transport", null);
        }

        @NonNull
        default NfcHolderTransport nfc(@NonNull NfcOptions options) {
            throw new UnsupportedTransportException(
                    "this transport builder has no NFC data retrieval transport", null);
        }
    }

    private final TransportRace race;
    private final Deadline deadline;
    private final Map<BleMode, UUID> serviceUuids;
    private final byte[] engagementBytes;
    private final byte[] nfcEngagementBytes;
    private final Integer l2capPsm;
    private final NfcOptions nfcOptions;
    private volatile NfcHolderTransport nfcTransport;
    private final Object stateLock = new Object();
    private final AtomicReference<Deadline.Cancellable> establishmentDeadline =
            new AtomicReference<>();
    private final AtomicReference<Deadline.Cancellable> responseDeadline =
            new AtomicReference<>();
    private final AtomicBoolean started = new AtomicBoolean();

    private volatile KeyPair eDeviceKeyPair;
    private volatile Listener listener;
    private volatile SessionCrypto crypto;
    private volatile byte[] sessionTranscript;
    private volatile boolean ended;
    private volatile boolean errorReported;
    private volatile byte[] latchedHandoverSelect;
    private volatile byte[] latchedHandoverRequest;

    private HolderSession(@NonNull Deadline deadline,
                          @NonNull TransferConfig config,
                          @Nullable WifiOptions wifi,
                          @Nullable L2capServer l2cap,
                          @Nullable NfcOptions nfc,
                          @NonNull TransportFactory factory) {
        this.deadline = deadline;
        this.nfcOptions = nfc;
        this.eDeviceKeyPair = CoseKeys.generateEphemeral();
        Map<BleMode, UUID> uuids = new LinkedHashMap<>();
        UUID shared = UUID.randomUUID();
        for (BleMode mode : config.bleMode().modes()) {
            uuids.put(mode, shared);
        }
        this.serviceUuids = Collections.unmodifiableMap(uuids);
        this.l2capPsm = l2cap != null && uuids.containsKey(BleMode.PERIPHERAL_SERVER)
                ? l2cap.psm() : null;
        List<DeviceRetrievalMethod> methods = new ArrayList<>();
        methods.add(DeviceRetrievalMethod.ble(serviceUuids).withPsm(l2capPsm));
        if (config.wifiAware() && wifi != null) {
            methods.add(DeviceRetrievalMethod.wifiAware(wifi));
        }
        DeviceEngagement engagement = DeviceEngagement.forQr(
                (ECPublicKey) eDeviceKeyPair.getPublic(), methods);
        this.engagementBytes = engagement.encode();
        this.nfcEngagementBytes = DeviceEngagement
                .forNfc((ECPublicKey) eDeviceKeyPair.getPublic()).encode();
        this.race = factory.create(engagement.eDeviceKeyBytes(), methods);
    }

    @NonNull
    public static HolderSession create(@NonNull Transport transport,
                                       @NonNull Deadline deadline,
                                       @NonNull BleMode mode) {
        return new HolderSession(deadline, TransferConfig.ble(BleTransferMode.of(mode)), null,
                null, null, (bytes, methods) -> TransportRace.of(Collections.singletonList(
                        TransportRace.Leg.of(TransportMode.of(mode), transport))));
    }

    @NonNull
    public static HolderSession create(@NonNull android.content.Context context) {
        return create(context, BleTransferMode.BOTH);
    }

    @NonNull
    public static HolderSession create(@NonNull android.content.Context context,
                                       @NonNull BleTransferMode transferMode) {
        return create(context, TransferConfig.ble(transferMode));
    }

    @NonNull
    public static HolderSession create(@NonNull android.content.Context context,
                                       @NonNull TransferConfig config) {
        HolderSession session = createForContext(context, config);
        NfcEngagementRegistry.arm(session);
        return session;
    }

    public static boolean isNfcEngagementAvailable(@NonNull android.content.Context context) {
        android.nfc.NfcAdapter adapter = android.nfc.NfcAdapter.getDefaultAdapter(context);
        return adapter != null
                && adapter.isEnabled()
                && context.getPackageManager().hasSystemFeature(
                        android.content.pm.PackageManager.FEATURE_NFC_HOST_CARD_EMULATION);
    }

    @NonNull
    private static HolderSession createForContext(@NonNull android.content.Context context,
                                                  @NonNull TransferConfig config) {
        WifiOptions wifi = null;
        if (config.wifiAware()) {
            if (WifiAwareSupport.isAvailable(context)) {
                wifi = WifiOptions.of(WifiAwareSupport.supportedBands(context));
            } else {
                ProximityLogger.getInstance().d(
                        "Wi-Fi Aware requested but unavailable, engaging over BLE only");
            }
        }
        NfcOptions nfc = null;
        if (isNfcEngagementAvailable(context)) {
            nfc = NfcOptions.of(NFC_MAX_COMMAND_LENGTH, NFC_MAX_RESPONSE_LENGTH);
        } else {
            ProximityLogger.getInstance().d(
                    "nfc hce unavailable, handover select omits the nfc carrier");
        }
        BluetoothL2capServer l2cap = openL2capServer(context, config);
        try {
            return create(new HandlerDeadline(), config, wifi, l2cap, nfc, new TransportBuilder() {
                @NonNull
                @Override
                public Transport centralClient(@NonNull UUID serviceUuid,
                                               @NonNull byte[] expectedIdent) {
                    return new GattClientTransport(context, serviceUuid,
                            GattProfile.MDOC_READER, expectedIdent, null,
                            L2capSupport.isAvailable(context));
                }

                @NonNull
                @Override
                public Transport peripheralServer(@NonNull UUID serviceUuid) {
                    return new GattServerTransport(context, serviceUuid);
                }

                @NonNull
                @Override
                public Transport peripheralServer(@NonNull UUID serviceUuid,
                                                  @Nullable L2capServer server) {
                    return new GattServerTransport(context, serviceUuid, GattProfile.MDOC,
                            null, (BluetoothL2capServer) server);
                }

                @NonNull
                @Override
                public Transport wifiAware(@NonNull byte[] eDeviceKeyBytes,
                                           @NonNull WifiOptions options) {
                    if (android.os.Build.VERSION.SDK_INT < WifiAwareSupport.MIN_SDK) {
                        throw new UnsupportedTransportException(
                                "Wi-Fi Aware needs API " + WifiAwareSupport.MIN_SDK, null);
                    }
                    return new WifiAwarePublisherTransport(context, eDeviceKeyBytes, options);
                }

                @NonNull
                @Override
                public NfcHolderTransport nfc(@NonNull NfcOptions options) {
                    return new NfcHolderTransport(options);
                }
            });
        } catch (RuntimeException e) {
            if (l2cap != null) {
                l2cap.close();
            }
            throw e;
        }
    }

    @Nullable
    private static BluetoothL2capServer openL2capServer(@NonNull android.content.Context context,
                                                        @NonNull TransferConfig config) {
        if (!config.bleMode().modes().contains(BleMode.PERIPHERAL_SERVER)) {
            return null;
        }
        if (android.os.Build.VERSION.SDK_INT < L2capSupport.MIN_SDK
                || !L2capSupport.isAvailable(context)) {
            ProximityLogger.getInstance().d("l2cap unavailable, engagement omits key 21");
            return null;
        }
        try {
            return BluetoothL2capServer.open(context);
        } catch (ProximityException e) {
            ProximityLogger.getInstance().d("l2cap unavailable, engagement omits key 21: "
                    + e.getMessage());
            return null;
        }
    }


    @NonNull
    static HolderSession create(@NonNull Deadline deadline,
                                @NonNull BleTransferMode transferMode,
                                @NonNull TransportBuilder builder) {
        return create(deadline, TransferConfig.ble(transferMode), null, builder);
    }

    @NonNull
    static HolderSession create(@NonNull Deadline deadline,
                                @NonNull TransferConfig config,
                                @Nullable WifiOptions wifi,
                                @NonNull TransportBuilder builder) {
        return create(deadline, config, wifi, null, builder);
    }

    @NonNull
    static HolderSession create(@NonNull Deadline deadline,
                                @NonNull TransferConfig config,
                                @Nullable WifiOptions wifi,
                                @Nullable L2capServer l2cap,
                                @NonNull TransportBuilder builder) {
        return create(deadline, config, wifi, l2cap, null, builder);
    }

    @NonNull
    static HolderSession create(@NonNull Deadline deadline,
                                @NonNull TransferConfig config,
                                @Nullable WifiOptions wifi,
                                @Nullable L2capServer l2cap,
                                @Nullable NfcOptions nfc,
                                @NonNull TransportBuilder builder) {
        AtomicReference<NfcHolderTransport> nfcLeg = new AtomicReference<>();
        HolderSession session = new HolderSession(deadline, config, wifi, l2cap, nfc,
                (bytes, methods) -> {
            List<TransportRace.Leg> legs = new ArrayList<>();
            for (DeviceRetrievalMethod method : methods) {
                if (method.isWifiAware()) {
                    legs.add(TransportRace.Leg.of(TransportMode.WIFI_AWARE,
                            builder.wifiAware(bytes, method.wifiOptions())));
                    continue;
                }
                for (BleMode mode : method.modes()) {
                    UUID uuid = method.serviceUuid(mode);
                    Transport transport = mode == BleMode.CENTRAL_CLIENT
                            ? builder.centralClient(uuid, BleIdent.derive(bytes))
                            : builder.peripheralServer(uuid, l2cap);
                    legs.add(TransportRace.Leg.of(TransportMode.of(mode), transport));
                }
            }
            if (nfc != null) {
                NfcHolderTransport transport = builder.nfc(nfc);
                nfcLeg.set(transport);
                legs.add(TransportRace.Leg.of(TransportMode.NFC, transport));
            }
            return TransportRace.of(legs);
        });
        session.nfcTransport = nfcLeg.get();
        return session;
    }

    @NonNull
    public String deviceEngagementQr() {
        return EngagementCodec.encode(engagementBytes);
    }

    public void start(@NonNull Listener listener) {
        if (!started.compareAndSet(false, true)) {
            throw new ProximityException("the session is already started");
        }
        this.listener = listener;
        establishmentDeadline.set(deadline.schedule(
                ENGAGEMENT_TIMEOUT_MS, this::onEngagementTimeout));
        try {
            open(listener);
        } catch (ProximityException e) {
            fail(e);
        }
    }

    private void open(@NonNull Listener listener) {
        race.start(new Transport.Listener() {
            @Override
            public void onConnected() {
                if (ended) {
                    return;
                }
                listener.onReaderConnected();
            }

            @Override
            public void onMessage(@NonNull byte[] message) {
                handleMessage(message);
            }

            @Override
            public void onError(@NonNull ProximityException error) {
                if (ended) {
                    return;
                }
                errorReported = true;
                fail(error);
            }

            @Override
            public void onDisconnected() {
                if (ended) {
                    return;
                }
                endSession();
                listener.onTerminated();
            }
        });
    }

    public void sendResponse(@NonNull byte[] deviceResponseCbor) {
        ProximityLogger.getInstance().d("sending device response, "
                + deviceResponseCbor.length + " bytes");
        byte[] frame = null;
        ProximityException failure = null;
        synchronized (stateLock) {
            SessionCrypto active = ended ? null : crypto;
            if (active == null) {
                failure = new ProximityException("no session established yet");
            } else {
                try {
                    frame = SessionMessages.data(active.encrypt(deviceResponseCbor));
                } catch (ProximityException e) {
                    failure = e;
                }
            }
        }
        if (failure != null) {
            fail(failure);
            return;
        }
        claimResponse();
        try {
            race.send(frame);
            Listener host = listener;
            if (host != null) {
                host.onResponseSent();
            }
        } catch (ProximityException e) {
            fail(e);
        }
    }

    public void terminate() {
        try {
            race.send(SessionMessages.termination());
        } catch (ProximityException e) {
            ProximityLogger.getInstance().d("termination send failed: " + e);
        } finally {
            endSession();
            if (listener != null) {
                listener.onTerminated();
            }
        }
    }

    public void closeWithoutNotifying() {
        endSession();
    }

    private void handleMessage(@NonNull byte[] message) {
        boolean establishing = false;
        Integer status = null;
        byte[] plaintext = null;
        byte[] transcriptSnapshot = null;
        ProximityException failure = null;
        synchronized (stateLock) {
            if (ended) {
                return;
            }
            SessionCrypto active = crypto;
            byte[] transcript = sessionTranscript;
            if (active == null) {
                establishing = true;
            } else if (transcript == null) {
                failure = new ProximityException("no session established yet");
            } else {
                try {
                    transcriptSnapshot = transcript.clone();
                    status = SessionMessages.parseStatus(message);
                    byte[] encrypted = SessionMessages.parseData(message);
                    if (encrypted != null) {
                        if (status != null && status.intValue()
                                != SessionMessages.STATUS_SESSION_TERMINATION) {
                            throw new ProximityException("data is present with status " + status);
                        }
                        plaintext = active.decrypt(encrypted);
                    }
                } catch (ProximityException e) {
                    failure = e;
                }
            }
        }
        if (establishing) {
            try {
                establish(message);
            } catch (ProximityException e) {
                fail(e);
            }
            return;
        }
        if (failure != null) {
            fail(failure);
            return;
        }
        try {
            if (plaintext == null) {
                endWithStatus(status);
                return;
            }
            deliver(plaintext, transcriptSnapshot);
            if (status != null) {
                endWithStatus(status);
            }
        } catch (ProximityException e) {
            fail(e);
        }
    }

    private void endWithStatus(@Nullable Integer status) {
        ProximityLogger.getInstance().d("reader ended the session with status " + status);
        endSession();
        if (status == null) {
            throw new ProximityException("session data carried neither data nor status");
        }
        if (status.intValue() == SessionMessages.STATUS_SESSION_TERMINATION) {
            listener.onTerminated();
            return;
        }
        throw new ProximityException("the reader ended the session with status " + status);
    }

    private boolean claimEstablishment() {
        Deadline.Cancellable pending = establishmentDeadline.getAndSet(null);
        if (pending == null) {
            return false;
        }
        pending.cancel();
        return true;
    }

    private boolean claimResponse() {
        Deadline.Cancellable pending = responseDeadline.getAndSet(null);
        if (pending == null) {
            return false;
        }
        pending.cancel();
        return true;
    }

    private void onEngagementTimeout() {
        if (!claimEstablishment() || ended) {
            return;
        }
        if (errorReported) {
            endSession();
            return;
        }
        fail(new ProximityException("session establishment did not complete in time",
                ProximityException.Reason.ENGAGEMENT_TIMEOUT));
    }

    private void onResponseTimeout() {
        if (!claimResponse() || ended) {
            return;
        }
        try {
            race.send(SessionMessages.termination());
        } catch (ProximityException e) {
            ProximityLogger.getInstance().d("termination send failed: " + e);
        }
        fail(new ProximityException("the holder did not respond in time",
                ProximityException.Reason.RESPONSE_TIMEOUT));
    }

    private void endSession() {
        NfcEngagementRegistry.disarm(this);
        claimEstablishment();
        claimResponse();
        synchronized (stateLock) {
            ended = true;
            SessionCrypto active = crypto;
            if (active != null) {
                active.destroy();
            }
            crypto = null;
            byte[] transcript = sessionTranscript;
            if (transcript != null) {
                Arrays.fill(transcript, (byte) 0x00);
            }
            sessionTranscript = null;
            eDeviceKeyPair = null;
        }
        race.stop();
    }

    private void establish(@NonNull byte[] message) {
        KeyPair keyPair = eDeviceKeyPair;
        if (keyPair == null) {
            return;
        }
        SessionMessages.Establishment establishment =
                SessionMessages.parseEstablishment(message);
        ECPublicKey eReaderKey = CoseKeys.toPublicKey(establishment.eReaderKeyBytes);

        byte[] transcript = null;
        SessionCrypto established = null;
        byte[] plaintext = null;
        EngagementMethod method = null;
        for (Candidate candidate : transcriptCandidates(establishment.eReaderKeyBytes)) {
            SessionCrypto trial = SessionCrypto.forDevice(SessionKeys.derive(
                    keyPair.getPrivate(), eReaderKey, candidate.transcript));
            try {
                plaintext = trial.decrypt(establishment.data);
                transcript = candidate.transcript;
                established = trial;
                method = candidate.method;
                break;
            } catch (ProximityException rejected) {
                trial.destroy();
                Arrays.fill(candidate.transcript, (byte) 0x00);
            }
        }
        if (established == null) {
            throw new ProximityException(
                    "no candidate session transcript decrypts the establishment message");
        }

        byte[] transcriptSnapshot;
        synchronized (stateLock) {
            if (!claimEstablishment() || ended) {
                established.destroy();
                Arrays.fill(transcript, (byte) 0x00);
                return;
            }
            sessionTranscript = transcript;
            crypto = established;
            transcriptSnapshot = transcript.clone();
        }

        Listener host = listener;
        if (host != null) {
            host.onEngagementDelivered(method);
        }
        deliver(plaintext, transcriptSnapshot);
    }

    private static final class Candidate {
        @NonNull final byte[] transcript;
        @NonNull final EngagementMethod method;

        Candidate(@NonNull byte[] transcript, @NonNull EngagementMethod method) {
            this.transcript = transcript;
            this.method = method;
        }
    }

    @NonNull
    private List<Candidate> transcriptCandidates(@NonNull byte[] eReaderKeyBytes) {
        Candidate qr = new Candidate(
                SessionTranscripts.forQr(engagementBytes, eReaderKeyBytes), EngagementMethod.QR);
        byte[] handoverSelect = latchedHandoverSelect;
        if (handoverSelect == null) {
            return Collections.singletonList(qr);
        }
        Candidate nfc = new Candidate(
                SessionTranscripts.forNfc(nfcEngagementBytes, eReaderKeyBytes,
                        handoverSelect, latchedHandoverRequest),
                EngagementMethod.NFC);
        return Arrays.asList(nfc, qr);
    }

    void onNfcHandoverServed(@NonNull byte[] handoverSelect, @Nullable byte[] handoverRequest) {
        ProximityLogger.getInstance().d("nfc handover select served, negotiated="
                + (handoverRequest != null));
        latchedHandoverRequest = handoverRequest == null ? null : handoverRequest.clone();
        latchedHandoverSelect = handoverSelect.clone();
    }

    @NonNull
    byte[] nfcEngagementBytes() {
        return nfcEngagementBytes.clone();
    }

    @NonNull
    Map<BleMode, UUID> serviceUuids() {
        return serviceUuids;
    }

    @Nullable
    Integer l2capPsm() {
        return l2capPsm;
    }

    @Nullable
    NfcOptions nfcOptions() {
        return nfcOptions;
    }

    @Nullable
    NfcHolderTransport nfcTransport() {
        return nfcTransport;
    }

    private void deliver(@NonNull byte[] deviceRequest, @NonNull byte[] transcript) {
        if (ended) {
            return;
        }
        ItemsRequest request = DeviceRequestParser.parse(deviceRequest);
        Listener host = listener;
        if (host == null) {
            return;
        }
        responseDeadline.set(deadline.schedule(RESPONSE_TIMEOUT_MS, this::onResponseTimeout));
        host.onRequest(new ProximityRequest(
                deviceRequest, request.docType(), request.requestedElements(),
                SessionTranscripts.taggedBytes(transcript), request.readerAuth()));
    }

    private void fail(@NonNull ProximityException error) {
        ProximityLogger.getInstance().e("holder session failed: " + error);
        endSession();
        Listener host = listener;
        if (host != null) {
            host.onError(error);
        }
    }
}
