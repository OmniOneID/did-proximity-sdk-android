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

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.omnione.did.proximity.sdk.core.ProximityException;
import org.omnione.did.proximity.sdk.core.cbor.CoseKeys;
import org.omnione.did.proximity.sdk.core.engagement.BleMode;
import org.omnione.did.proximity.sdk.core.engagement.DeviceEngagement;
import org.omnione.did.proximity.sdk.core.engagement.DeviceRetrievalMethod;
import org.omnione.did.proximity.sdk.core.engagement.EngagementCodec;
import org.omnione.did.proximity.sdk.core.engagement.ModePreference;
import org.omnione.did.proximity.sdk.core.engagement.NfcOptions;
import org.omnione.did.proximity.sdk.core.engagement.SessionTranscripts;
import org.omnione.did.proximity.sdk.core.engagement.TransportMode;
import org.omnione.did.proximity.sdk.core.engagement.TransportPreference;
import org.omnione.did.proximity.sdk.core.engagement.UnsupportedTransportException;
import org.omnione.did.proximity.sdk.core.logger.ProximityLogger;
import org.omnione.did.proximity.sdk.core.message.DeviceAuthResult;
import org.omnione.did.proximity.sdk.core.message.DeviceAuthVerifier;
import org.omnione.did.proximity.sdk.core.message.DeviceRequestBuilder;
import org.omnione.did.proximity.sdk.core.message.DeviceResponse;
import org.omnione.did.proximity.sdk.core.message.DeviceResponseParser;
import org.omnione.did.proximity.sdk.core.message.Document;
import org.omnione.did.proximity.sdk.core.message.ItemsRequest;
import org.omnione.did.proximity.sdk.core.nfc.ApduChannel;
import org.omnione.did.proximity.sdk.core.nfc.NfcHandoverResult;
import org.omnione.did.proximity.sdk.core.nfc.NfcHandoverResults;
import org.omnione.did.proximity.sdk.core.session.SessionCrypto;
import org.omnione.did.proximity.sdk.core.session.SessionKeys;
import org.omnione.did.proximity.sdk.core.session.SessionMessages;
import org.omnione.did.proximity.sdk.core.transport.Transport;
import org.omnione.did.proximity.sdk.core.transport.ble.BleIdent;
import org.omnione.did.proximity.sdk.core.transport.ble.BluetoothL2capServer;
import org.omnione.did.proximity.sdk.core.transport.ble.GattClientTransport;
import org.omnione.did.proximity.sdk.core.transport.ble.GattProfile;
import org.omnione.did.proximity.sdk.core.transport.ble.GattServerTransport;
import org.omnione.did.proximity.sdk.core.transport.ble.L2capSupport;
import org.omnione.did.proximity.sdk.core.transport.nfc.NfcReaderTransport;
import org.omnione.did.proximity.sdk.core.transport.wifiaware.WifiAwareSubscriberTransport;
import org.omnione.did.proximity.sdk.core.transport.wifiaware.WifiAwareSupport;
import org.omnione.did.proximity.sdk.core.util.Deadline;
import org.omnione.did.proximity.sdk.core.util.HandlerDeadline;

import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.interfaces.ECPublicKey;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class ReaderSession {

    public interface Listener {
        void onMdocConnected();

        void onResponse(@NonNull ProximityResponse response);

        void onError(@NonNull ProximityException error);

        void onTerminated();
    }

    public static final long CONNECT_TIMEOUT_MS = 30_000L;
    public static final long RESPONSE_TIMEOUT_MS = 120_000L;
    public static final long TERMINATION_GRACE_MS = 250L;

    private volatile Transport transport;
    private final Deadline deadline;
    private final Context context;
    private final TransportPreference preference;
    private final boolean useL2cap;
    private final Object stateLock = new Object();

    private volatile KeyPair eReaderKeyPair;
    private volatile Listener listener;
    private volatile SessionCrypto crypto;
    private volatile byte[] sessionTranscript;
    private volatile byte[] pendingEstablishment;
    private volatile boolean ended;
    private volatile boolean errorReported;
    private volatile boolean responding;
    private final AtomicReference<Deadline.Cancellable> terminationStop = new AtomicReference<>();
    private final AtomicReference<Deadline.Cancellable> connectDeadline =
            new AtomicReference<>();
    private final AtomicReference<Deadline.Cancellable> responseDeadline =
            new AtomicReference<>();
    private final AtomicBoolean started = new AtomicBoolean();

    private ReaderSession(@Nullable Transport transport,
                          @NonNull TransportPreference preference,
                          @NonNull Deadline deadline) {
        this.transport = transport;
        this.deadline = deadline;
        this.context = null;
        this.preference = preference;
        this.useL2cap = false;
        this.eReaderKeyPair = CoseKeys.generateEphemeral();
    }

    private ReaderSession(@NonNull Context context,
                          @NonNull TransportPreference preference,
                          @NonNull ReaderOptions options,
                          @NonNull Deadline deadline) {
        this.transport = null;
        this.deadline = deadline;
        this.context = context.getApplicationContext();
        this.preference = preference;
        this.useL2cap = l2capOnThisDevice(this.context, options);
        this.eReaderKeyPair = CoseKeys.generateEphemeral();
    }

    @NonNull
    public static ReaderSession create(@NonNull Transport transport) {
        return create(transport, new HandlerDeadline());
    }

    @NonNull
    public static ReaderSession create(@NonNull Transport transport,
                                       @NonNull Deadline deadline) {
        return create(transport, deadline,
                TransportPreference.from(ModePreference.CENTRAL_CLIENT_FIRST));
    }

    @NonNull
    public static ReaderSession create(@NonNull Transport transport,
                                       @NonNull Deadline deadline,
                                       @NonNull TransportPreference preference) {
        return new ReaderSession(transport, preference, deadline);
    }

    @NonNull
    static ReaderSession create(@NonNull TransportPreference preference,
                                @NonNull Deadline deadline) {
        return new ReaderSession(null, preference, deadline);
    }

    @NonNull
    public static ReaderSession create(@NonNull Context context) {
        return create(context, ModePreference.CENTRAL_CLIENT_FIRST);
    }

    @NonNull
    public static ReaderSession create(@NonNull Context context,
                                       @NonNull ModePreference preference) {
        return create(context, TransportPreference.from(preference));
    }

    @NonNull
    public static ReaderSession create(@NonNull Context context,
                                       @NonNull TransportPreference preference) {
        return create(context, preference, ReaderOptions.defaults());
    }

    @NonNull
    public static ReaderSession create(@NonNull Context context,
                                       @NonNull TransportPreference preference,
                                       @NonNull ReaderOptions options) {
        return new ReaderSession(context, supportedOnThisDevice(preference), options,
                new HandlerDeadline());
    }

    private static boolean l2capOnThisDevice(@NonNull Context context,
                                             @NonNull ReaderOptions options) {
        if (!options.l2cap()) {
            return false;
        }
        if (!L2capSupport.isAvailable(context)) {
            ProximityLogger.getInstance().d("l2cap requested but unavailable, using gatt");
            return false;
        }
        return true;
    }

    @NonNull
    private static TransportPreference supportedOnThisDevice(
            @NonNull TransportPreference preference) {
        if (android.os.Build.VERSION.SDK_INT >= WifiAwareSupport.MIN_SDK
                || !preference.order().contains(TransportMode.WIFI_AWARE)
                || preference.order().size() == 1) {
            return preference;
        }
        ProximityLogger.getInstance().d("Wi-Fi Aware needs API " + WifiAwareSupport.MIN_SDK
                + ", dropping it from the transport preference");
        return preference.without(TransportMode.WIFI_AWARE);
    }

    public void start(@NonNull String engagementQr,
                      @NonNull ItemsRequest request,
                      @NonNull Listener listener) {
        begin(request, listener, () -> fromQr(engagementQr, preference));
    }

    public void start(@NonNull NfcHandoverResult handover,
                      @NonNull ItemsRequest request,
                      @NonNull Listener listener) {
        begin(request, listener, () -> fromNfc(handover));
        if (ended && transport == null) {
            NfcHandoverResults.closeQuietly(handover);
        }
    }

    private void begin(@NonNull ItemsRequest request,
                       @NonNull Listener listener,
                       @NonNull java.util.function.Supplier<Engagement> engagement) {
        if (!started.compareAndSet(false, true)) {
            throw new ProximityException("the session is already started");
        }
        this.listener = listener;
        try {
            open(engagement.get(), request, listener);
        } catch (ProximityException e) {
            fail(e, -1);
        }
    }

    private static final class Engagement {
        @NonNull final ECPublicKey eDeviceKey;
        @NonNull final byte[] eDeviceKeyBytes;
        @NonNull final DeviceRetrievalMethod method;
        @NonNull final Function<byte[], byte[]> transcript;
        @Nullable final ApduChannel channel;

        Engagement(@NonNull ECPublicKey eDeviceKey,
                   @NonNull byte[] eDeviceKeyBytes,
                   @NonNull DeviceRetrievalMethod method,
                   @NonNull Function<byte[], byte[]> transcript,
                   @Nullable ApduChannel channel) {
            this.eDeviceKey = eDeviceKey;
            this.eDeviceKeyBytes = eDeviceKeyBytes;
            this.method = method;
            this.transcript = transcript;
            this.channel = channel;
        }
    }

    @NonNull
    static TransportPreference withoutNfc(@NonNull TransportPreference preference) {
        if (!preference.order().contains(TransportMode.NFC)) {
            return preference;
        }
        if (preference.order().size() == 1) {
            throw new UnsupportedTransportException(
                    "NFC data retrieval needs an NFC tap; engage over NFC instead", null);
        }
        ProximityLogger.getInstance().d("dropping NFC from the QR preference");
        return preference.without(TransportMode.NFC);
    }

    @NonNull
    private static Engagement fromQr(@NonNull String engagementQr,
                                     @NonNull TransportPreference preference) {
        byte[] engagementBytes = EngagementCodec.decode(engagementQr);
        DeviceEngagement engagement =
                DeviceEngagement.parse(engagementBytes, withoutNfc(preference));
        return new Engagement(engagement.eDeviceKey(), engagement.eDeviceKeyBytes(),
                engagement.retrievalMethod(),
                eReaderKeyBytes -> SessionTranscripts.forQr(engagementBytes, eReaderKeyBytes),
                null);
    }

    @NonNull
    private static Engagement fromNfc(@NonNull NfcHandoverResult handover) {
        if (handover.selected() == TransportMode.NFC) {
            NfcOptions limits = handover.nfcOptions();
            if (limits == null) {
                throw new UnsupportedTransportException(
                        "the NFC handover carries no NFC data retrieval limits", null);
            }
            return new Engagement(handover.eDeviceKey(), handover.eDeviceKeyBytes(),
                    DeviceRetrievalMethod.nfc(limits), handover::sessionTranscript,
                    handover.channel());
        }
        BleMode mode = handover.mode();
        UUID serviceUuid = handover.serviceUuid();
        if (mode == null || serviceUuid == null) {
            throw new UnsupportedTransportException(
                    "the NFC handover selected " + handover.selected()
                            + " without a BLE carrier", null);
        }
        DeviceRetrievalMethod method = DeviceRetrievalMethod.ble(
                Collections.singletonMap(mode, serviceUuid));
        if (mode == BleMode.PERIPHERAL_SERVER && handover.psm() != null) {
            method = method.withPsm(handover.psm());
        }
        return new Engagement(handover.eDeviceKey(), handover.eDeviceKeyBytes(), method,
                handover::sessionTranscript, null);
    }

    private void open(@NonNull Engagement engagement,
                      @NonNull ItemsRequest request,
                      @NonNull Listener listener) {
        KeyPair keyPair;
        synchronized (stateLock) {
            if (ended) {
                return;
            }
            keyPair = eReaderKeyPair;
        }
        if (transport == null) {
            transport = transportFor(engagement);
        }

        byte[] eReaderKeyBytes = CoseKeys
                .toCoseKey((ECPublicKey) keyPair.getPublic())
                .EncodeToBytes();
        byte[] transcript = engagement.transcript.apply(eReaderKeyBytes);
        SessionCrypto established = SessionCrypto.forReader(SessionKeys.derive(
                keyPair.getPrivate(), engagement.eDeviceKey, transcript));
        byte[] establishment = SessionMessages.establishment(
                eReaderKeyBytes, established.encrypt(DeviceRequestBuilder.build(request)));

        boolean live;
        synchronized (stateLock) {
            live = !ended;
            if (live) {
                sessionTranscript = transcript;
                crypto = established;
                pendingEstablishment = establishment;
            }
        }
        if (!live) {
            established.destroy();
            Arrays.fill(transcript, (byte) 0x00);
            stopQuietly(transport);
            return;
        }

        connectDeadline.set(
                deadline.schedule(CONNECT_TIMEOUT_MS, this::onConnectTimeout));

        transport.start(new Transport.Listener() {
            @Override
            public void onConnected() {
                boolean claimed = claimConnect();
                ProximityLogger.getInstance().d("onConnected claimed=" + claimed
                        + " ended=" + ended);
                if (!claimed || ended) {
                    return;
                }
                listener.onMdocConnected();
                byte[] establishment = pendingEstablishment;
                pendingEstablishment = null;
                if (establishment == null) {
                    ProximityLogger.getInstance().e("no pending establishment to send");
                    return;
                }
                ProximityLogger.getInstance().d("sending session establishment, "
                        + establishment.length + " bytes");
                transport.send(establishment);
                responseDeadline.set(deadline.schedule(
                        RESPONSE_TIMEOUT_MS, ReaderSession.this::onResponseTimeout));
                ProximityLogger.getInstance().d("session establishment handed to transport");
            }

            @Override
            public void onMessage(@NonNull byte[] message) {
                ProximityLogger.getInstance().d("onMessage " + message.length + " bytes");
                handleMessage(message);
            }

            @Override
            public void onError(@NonNull ProximityException error) {
                ProximityLogger.getInstance().e("transport error, ended=" + ended
                        + ": " + error);
                if (ended) {
                    return;
                }
                errorReported = true;
                listener.onError(error);
            }

            @Override
            public void onDisconnected() {
                ProximityLogger.getInstance().d("transport disconnected, ended=" + ended
                        + " responding=" + responding);
                synchronized (stateLock) {
                    if (responding || !claimEnd()) {
                        return;
                    }
                }
                endSession();
                listener.onTerminated();
            }
        });
        if (ended) {
            stopQuietly(transport);
        }
    }

    @NonNull
    private Transport transportFor(@NonNull Engagement engagement) {
        DeviceRetrievalMethod method = engagement.method;
        switch (method.transportMode()) {
            case BLE_CENTRAL_CLIENT:
                ProximityLogger.getInstance().d("using mdoc central client mode, reader"
                        + " advertises " + method.serviceUuid()
                        + " l2cap=" + (useL2cap ? "on" : "off"));
                return new GattServerTransport(context, method.serviceUuid(),
                        GattProfile.MDOC_READER, BleIdent.derive(engagement.eDeviceKeyBytes),
                        useL2cap ? openL2capServer() : null);
            case BLE_PERIPHERAL_SERVER:
                ProximityLogger.getInstance().d("using mdoc peripheral server mode, reader"
                        + " scans for " + method.serviceUuid()
                        + " l2cap=" + (useL2cap ? "on" : "off") + " psm=" + method.psm());
                return new GattClientTransport(context, method.serviceUuid(), GattProfile.MDOC,
                        null, useL2cap ? method.psm() : null, useL2cap);
            case WIFI_AWARE:
                if (android.os.Build.VERSION.SDK_INT < WifiAwareSupport.MIN_SDK) {
                    throw new UnsupportedTransportException(
                            "Wi-Fi Aware needs API " + WifiAwareSupport.MIN_SDK, null);
                }
                ProximityLogger.getInstance().d("using Wi-Fi Aware, reader subscribes");
                return new WifiAwareSubscriberTransport(context, engagement.eDeviceKeyBytes,
                        method.wifiOptions());
            case NFC:
                if (engagement.channel == null) {
                    throw new UnsupportedTransportException(
                            "NFC data retrieval needs the tapped tag's channel", null);
                }
                NfcReaderTransport nfc =
                        new NfcReaderTransport(engagement.channel, method.nfcOptions());
                ProximityLogger.getInstance().d("using nfc data retrieval maxCmd="
                        + method.nfcOptions().maxCommandLength()
                        + " maxResp=" + method.nfcOptions().maxResponseLength()
                        + " extended=" + nfc.extendedLength()
                        + " chunk=" + nfc.chunkLength() + " le=" + nfc.le());
                return nfc;
            default:
                throw new UnsupportedTransportException(
                        "no transport for " + method.transportMode(), null);
        }
    }

    @Nullable
    private BluetoothL2capServer openL2capServer() {
        if (android.os.Build.VERSION.SDK_INT < L2capSupport.MIN_SDK) {
            return null;
        }
        try {
            return BluetoothL2capServer.open(context);
        } catch (ProximityException e) {
            ProximityLogger.getInstance().d("l2cap server unavailable, using gatt: "
                    + e.getMessage());
            return null;
        }
    }

    public void terminate() {
        ProximityLogger.getInstance().d("terminate requested, ended=" + ended);
        boolean claimed = claimEnd();
        try {
            closeTransport(claimed && started.get());
        } finally {
            Listener host = listener;
            if (claimed && host != null) {
                host.onTerminated();
            }
        }
    }

    private void closeTransport(boolean sendTermination) {
        Transport active = transport;
        boolean sent = false;
        try {
            if (sendTermination && active != null) {
                active.send(SessionMessages.termination());
                sent = true;
            }
        } catch (RuntimeException e) {
            ProximityLogger.getInstance().d("termination send failed: " + e);
        } finally {
            endSession();
            if (active != null) {
                if (sent) {
                    stopAfterTerminationGrace(active);
                } else {
                    stopQuietly(active);
                }
            }
        }
    }

    public void closeWithoutNotifying() {
        ProximityLogger.getInstance().d("close requested, ended=" + ended);
        endSession();
        cancelTerminationStop();
        Transport active = transport;
        if (active != null) {
            active.stop();
        }
    }

    private void stopAfterTerminationGrace(@NonNull Transport active) {
        Deadline.Cancellable scheduled =
                deadline.schedule(TERMINATION_GRACE_MS, () -> stopQuietly(active));
        Deadline.Cancellable previous = terminationStop.getAndSet(scheduled);
        if (previous != null) {
            previous.cancel();
        }
    }

    private void cancelTerminationStop() {
        Deadline.Cancellable pending = terminationStop.getAndSet(null);
        if (pending != null) {
            pending.cancel();
        }
    }

    private static void stopQuietly(@NonNull Transport active) {
        try {
            active.stop();
        } catch (RuntimeException e) {
            ProximityLogger.getInstance().d("transport stop failed: " + e);
        }
    }

    private void handleMessage(@NonNull byte[] message) {
        Integer status;
        byte[] encrypted;
        try {
            status = SessionMessages.parseStatus(message);
            encrypted = SessionMessages.parseData(message);
        } catch (ProximityException e) {
            fail(e, SessionMessages.STATUS_CBOR_DECODING_ERROR);
            return;
        }
        ProximityLogger.getInstance().d("message parsed, status=" + status
                + " encryptedBytes=" + (encrypted == null ? -1 : encrypted.length));
        if (encrypted == null) {
            if (!claimEnd()) {
                return;
            }
            ProximityException peerFailure =
                    status != null && status != SessionMessages.STATUS_SESSION_TERMINATION
                            ? new ProximityException(
                                    "the mdoc ended the session with status " + status)
                            : null;
            closeTransport(false);
            if (peerFailure != null) {
                listener.onError(peerFailure);
            }
            listener.onTerminated();
            return;
        }

        byte[] transcriptSnapshot = null;
        byte[] plaintext = null;
        DeviceResponse parsed = null;
        PrivateKey readerPrivate = null;
        ProximityException failure = null;
        int failureStatus = -1;
        synchronized (stateLock) {
            if (ended) {
                return;
            }
            SessionCrypto active = crypto;
            KeyPair readerPair = eReaderKeyPair;
            readerPrivate = readerPair == null ? null : readerPair.getPrivate();
            byte[] transcript = sessionTranscript;
            transcriptSnapshot = transcript == null ? null : transcript.clone();
            if (active == null) {
                failure = new ProximityException("no session established yet");
            } else {
                try {
                    plaintext = active.decrypt(encrypted);
                    parsed = DeviceResponseParser.parse(plaintext);
                    responding = true;
                } catch (ProximityException e) {
                    failure = e;
                    failureStatus = plaintext == null
                            ? SessionMessages.STATUS_SESSION_ENCRYPTION_ERROR
                            : SessionMessages.STATUS_CBOR_DECODING_ERROR;
                }
            }
        }

        if (failure != null) {
            fail(failure, failureStatus);
            return;
        }

        claimResponse();
        List<DeviceAuthResult> deviceAuth = verifyDeviceAuth(
                plaintext, transcriptSnapshot, readerPrivate);
        logDeviceAuth(parsed.documents, deviceAuth);
        if (!claimEnd()) {
            return;
        }
        listener.onResponse(new ProximityResponse(
                parsed.documents, parsed.status, plaintext, transcriptSnapshot, deviceAuth));
        closeTransport(status == null);
        listener.onTerminated();
    }

    @NonNull
    private static List<DeviceAuthResult> verifyDeviceAuth(@NonNull byte[] plaintext,
                                                           @Nullable byte[] transcript,
                                                           @Nullable PrivateKey readerPrivate) {
        if (transcript == null) {
            return Collections.emptyList();
        }
        try {
            return DeviceAuthVerifier.verify(plaintext, transcript, readerPrivate);
        } catch (RuntimeException e) {
            ProximityLogger.getInstance().e("device auth verification could not run: " + e);
            return Collections.emptyList();
        }
    }

    private static void logDeviceAuth(@NonNull List<Document> documents,
                                      @NonNull List<DeviceAuthResult> results) {
        ProximityLogger logger = ProximityLogger.getInstance();
        if (documents.size() != results.size()) {
            logger.e("device auth verdicts " + results.size()
                    + " do not match documents " + documents.size());
        }
        for (int i = 0; i < documents.size(); i++) {
            String docType = documents.get(i) == null ? "?" : documents.get(i).docType;
            if (i >= results.size()) {
                logger.e("device auth [" + i + "] docType=" + docType + " verdict=missing");
                continue;
            }
            DeviceAuthResult result = results.get(i);
            String line = "device auth [" + i + "] docType=" + docType
                    + " outcome=" + result.outcome + " method=" + result.method;
            if (result.verified()) {
                logger.d(line);
            } else {
                logger.e(line + " detail=" + result.detail);
            }
        }
    }

    private void fail(@NonNull ProximityException error, int status) {
        ProximityLogger.getInstance().e("reader session failed: " + error);
        boolean alreadyEnded = !claimEnd();
        Transport active = transport;
        if (status >= 0 && !alreadyEnded && active != null) {
            try {
                active.send(SessionMessages.status(status));
            } catch (RuntimeException e) {
                ProximityLogger.getInstance().d("status send failed: " + e);
            }
        }
        endSession();
        if (active != null) {
            active.stop();
        }
        if (!alreadyEnded) {
            listener.onError(error);
        }
    }

    private void onConnectTimeout() {
        if (!claimConnect() || ended) {
            return;
        }
        if (errorReported) {
            endSession();
            Transport active = transport;
            if (active != null) {
                active.stop();
            }
            return;
        }
        fail(new ProximityException("the mdoc did not connect in time",
                ProximityException.Reason.ENGAGEMENT_TIMEOUT), -1);
    }

    private void onResponseTimeout() {
        if (!claimResponse() || ended) {
            return;
        }
        fail(new ProximityException("the mdoc did not respond in time",
                ProximityException.Reason.RESPONSE_TIMEOUT), -1);
    }

    private boolean claimConnect() {
        Deadline.Cancellable pending = connectDeadline.getAndSet(null);
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

    private boolean claimEnd() {
        synchronized (stateLock) {
            if (ended) {
                return false;
            }
            ended = true;
            return true;
        }
    }

    private void endSession() {
        claimConnect();
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
            pendingEstablishment = null;
            eReaderKeyPair = null;
        }
    }
}
