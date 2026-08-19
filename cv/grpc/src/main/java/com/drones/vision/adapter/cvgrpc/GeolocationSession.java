package com.drones.vision.adapter.cvgrpc;

import com.drones.vision.kernel.StreamId;
import com.drones.vision.kernel.Telemetry;
import com.drones.vision.kernel.VisualFix;
import com.drones.vision.perception.domain.model.GeoSessionConfig;
import com.drones.vision.proto.v1.GeoControl;
import com.drones.vision.proto.v1.GeoFix;
import com.drones.vision.proto.v1.GeolocationGrpc;
import io.grpc.stub.StreamObserver;

import java.net.URI;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One open {@code LocalizeStream} bidi call for a single {@link StreamId} — the geolocation sibling
 * of {@link PulledDetectionSession} (docs/plans/active/VISUAL-GEO-V2-PLAN.md §3.1, H3). Structurally
 * simpler than its detection cousin: {@link com.drones.vision.perception.domain.port.PulledGeolocationPort}
 * has no {@code reconfigure} method at all — {@link GeoSessionConfig} (region/target-fps/prior) is
 * fixed for a session's whole life, captured once at construction; only {@link
 * #telemetry(Telemetry)} is hot.
 *
 * <h2>Restated desired state (D2)</h2>
 * {@code GeoControl} is declarative, same doctrine as {@code PullControl}: a one-shot control
 * message can be lost with no error, so every message restates the complete current desired state —
 * {@code stream_id}/{@code region_id}/{@code target_fps}/{@code prior} from the fixed {@link
 * #config}, plus the last-known {@link #currentTelemetry} (or none, if telemetry has not arrived
 * yet). {@code source_url} is sent on the first message only ({@link #start}) and never restated
 * (the wire contract says later values are ignored).
 *
 * <h2>Failure semantics</h2>
 * Mirrors {@link PulledDetectionSession} exactly:
 * <ul>
 *   <li><b>Transport failure</b>: surfaced via {@link SubmissionPublisher#closeExceptionally}; this
 *   session drops itself from the owning map. No retry here (reopen-with-backoff belongs to the
 *   caller's own supervised publisher, not this adapter).</li>
 *   <li><b>Server ends the call normally</b>: expected only after this session's own {@link
 *   #endAndClose}; an unrequested server-side completion is treated as a failure.</li>
 *   <li><b>A malformed {@code GeoFix}</b> (one that fails {@link VisualFix}'s own compact-constructor
 *   validation, e.g. {@code status == GEO_STATUS_FIX} with no latitude): logged at WARNING and
 *   dropped — there is no pending future to fail for an unsolicited response, and the session keeps
 *   running for the next fix. A <em>refused</em> fix (a non-empty {@code refusal}, {@code position ==
 *   null}) is not a malformed response — {@link VisualFix}'s own javadoc: "a refusal, not an error" —
 *   and is delivered to the subscriber exactly like an accepted one.</li>
 * </ul>
 *
 * <p>gRPC stream observers are not thread-safe: every write to the request observer is serialized
 * through {@link #writeLock}. Teardown is idempotent via {@link #torndown} (an {@link AtomicBoolean}
 * CAS guard), same discipline as {@link PulledDetectionSession}.
 */
final class GeolocationSession {

    private static final System.Logger LOG = System.getLogger(GeolocationSession.class.getName());

    private final StreamId streamId;
    private final GeolocationGrpc.GeolocationStub asyncStub;
    private final ConcurrentHashMap<StreamId, GeolocationSession> sessions;
    private final GeoSessionConfig config;
    private final SubmissionPublisher<VisualFix> publisher = new SubmissionPublisher<>();
    private final Object writeLock = new Object();
    private final AtomicBoolean torndown = new AtomicBoolean(false);

    private volatile StreamObserver<GeoControl> requestObserver;
    private volatile Telemetry currentTelemetry;

    GeolocationSession(StreamId streamId, GeolocationGrpc.GeolocationStub asyncStub,
            ConcurrentHashMap<StreamId, GeolocationSession> sessions, GeoSessionConfig config) {
        this.streamId = streamId;
        this.asyncStub = asyncStub;
        this.sessions = sessions;
        this.config = config;
    }

    Flow.Publisher<VisualFix> publisher() {
        return publisher;
    }

    /** Opens the call and sends the first {@code GeoControl} — the only message carrying {@code source_url}. */
    void start(URI sourceUrl) {
        send(controlBuilder(false).setSourceUrl(sourceUrl.toString()).build());
    }

    /** Restates the full desired state with the new telemetry sample; latest-wins, per D2. */
    void telemetry(Telemetry telemetry) {
        currentTelemetry = telemetry;
        send(controlBuilder(false).build());
    }

    /** {@code stream_id}/{@code region_id}/{@code target_fps}/{@code prior} from {@link #config}, plus telemetry. */
    private GeoControl.Builder controlBuilder(boolean stop) {
        GeoControl.Builder builder = GeoFixCodec.toWireGeoControlBuilder(streamId.value().toString(), config)
                .setStop(stop);
        Telemetry telemetry = currentTelemetry;
        if (telemetry != null) {
            builder.setTelemetry(GeoFixCodec.toWireGeoTelemetry(telemetry));
        }
        return builder;
    }

    private void send(GeoControl control) {
        if (torndown.get()) {
            return; // already failed/closed -- no point attempting a write that can only be rejected
        }
        synchronized (writeLock) {
            try {
                openIfNeeded().onNext(control);
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING,
                        () -> "Failed to send GeoControl on geolocation stream " + streamId, e);
                failAndDrop(e);
            }
        }
    }

    /** Fails the publisher and drops this session so the next {@code open()} for this id starts fresh. */
    private void failAndDrop(Throwable cause) {
        if (!torndown.compareAndSet(false, true)) {
            return;
        }
        sessions.remove(streamId, this);
        publisher.closeExceptionally(cause);
    }

    /** Must be called while holding {@link #writeLock}. */
    private StreamObserver<GeoControl> openIfNeeded() {
        StreamObserver<GeoControl> observer = requestObserver;
        if (observer == null) {
            LOG.log(System.Logger.Level.INFO, () -> "Opening geolocation stream for " + streamId);
            observer = asyncStub.localizeStream(new ResponseHandler());
            requestObserver = observer;
        }
        return observer;
    }

    private void onResponse(GeoFix response) {
        VisualFix fix;
        try {
            fix = GeoFixCodec.decode(response);
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING,
                    () -> "Malformed geo fix on stream " + streamId + "; dropping it", e);
            return;
        }
        // Latest-wins backpressure: never block delivery for a slow subscriber (same drop policy as
        // PulledDetectionSession/VideoSourcePort).
        publisher.offer(fix, (subscriber, dropped) -> true);
    }

    private void onTransportError(Throwable t) {
        LOG.log(System.Logger.Level.WARNING, () -> "Geolocation stream for " + streamId + " failed", t);
        failAndDrop(t);
    }

    private void onServerCompleted() {
        // A live bidi call is never expected to complete server-side on its own. If this session's
        // own endAndClose() already tore it down (torndown already true), failAndDrop is a no-op.
        failAndDrop(new IllegalStateException("Geolocation stream for " + streamId + " completed unexpectedly"));
    }

    /** Sends {@code stop=true}, half-closes, and completes the publisher normally. Idempotent. */
    void endAndClose() {
        if (!torndown.compareAndSet(false, true)) {
            return;
        }
        sessions.remove(streamId, this);
        synchronized (writeLock) {
            StreamObserver<GeoControl> observer = requestObserver;
            if (observer != null) {
                try {
                    observer.onNext(controlBuilder(true).build());
                    observer.onCompleted();
                } catch (RuntimeException e) {
                    LOG.log(System.Logger.Level.DEBUG,
                            () -> "Ignoring error stopping geolocation stream " + streamId, e);
                }
            }
        }
        publisher.close();
    }

    private final class ResponseHandler implements StreamObserver<GeoFix> {
        @Override
        public void onNext(GeoFix response) {
            onResponse(response);
        }

        @Override
        public void onError(Throwable t) {
            onTransportError(t);
        }

        @Override
        public void onCompleted() {
            onServerCompleted();
        }
    }
}
