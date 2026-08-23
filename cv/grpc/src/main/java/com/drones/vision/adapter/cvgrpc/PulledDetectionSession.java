package com.drones.vision.adapter.cvgrpc;

import com.drones.vision.perception.domain.model.CameraAttitude;
import com.drones.vision.perception.domain.model.DetectionResult;
import com.drones.vision.perception.domain.model.PipelineConfig;
import com.drones.vision.kernel.StreamId;
import com.drones.vision.proto.v1.DetectionResponse;
import com.drones.vision.proto.v1.InferenceGrpc;
import com.drones.vision.proto.v1.PullControl;
import io.grpc.stub.StreamObserver;

import java.net.URI;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One open {@code DetectPulled} bidi call for a single {@link StreamId} — the pull-mode sibling of
 * {@link DetectionStreamSession}, docs/plans/done/MEDIA-SOT-PLAN.md wave M4.
 *
 * <h2>The structural difference from {@link DetectionStreamSession}</h2>
 * {@code DetectPulled} responses are <b>unsolicited</b>: the worker mints {@code sequence} itself,
 * monotonically per pull, and nothing on the client side asked for any particular response. There is
 * therefore <b>no sequence&rarr;future correlation map here</b> — every {@link DetectionResponse} that
 * arrives is decoded and pushed straight onto this session's own {@link Flow.Publisher}, in receipt
 * order, with no matching outbound request to pair it against. This is the one piece of {@link
 * DetectionStreamSession}'s machinery this class deliberately does not have.
 *
 * <h2>What replaces it: restated desired state</h2>
 * {@code PullControl} is declarative (docs/plans/done/MEDIA-SOT-PLAN.md &sect;5.1, mirroring {@code
 * TrackingConfig}'s own doctrine): a one-shot control message can be lost with no error and no retry
 * (cv-service's {@code LatestOnlyMailbox} silently drops a frame when the sender outruns the
 * consumer), so every message this class sends restates the <em>complete</em> current desired state,
 * not just what changed. {@link #reconfigure} and {@link #attitude} are called independently, at
 * different cadences (a config change vs. telemetry arriving), so this session remembers the
 * last-known {@link PipelineConfig} and {@link CameraAttitude} and merges both into every outbound
 * {@code PullControl} — including one triggered by only one of the two changing. {@code stream_id} is
 * sent on every message; {@code source_url}/{@code rtsp_transport} are sent on the first message only
 * ({@link #start}) and never restated (the wire contract says later values are ignored, so this class
 * does not bother setting them again).
 *
 * <h2>Failure semantics</h2>
 * <ul>
 *   <li><b>Transport failure</b> ({@code onError} from the server, e.g. {@code UNAVAILABLE} because
 *   the pulled source is unopenable or stalled past the worker's own timeout): surfaced to the
 *   publisher's subscriber via {@link SubmissionPublisher#closeExceptionally}, and this session drops
 *   itself from the owning map. No retry happens here — docs/plans/done/MEDIA-SOT-PLAN.md decision D5 is
 *   explicit that reopen-with-backoff is the existing generic {@code
 *   SupervisedPublisher<DetectionResult>}'s job (vision-application), not this adapter's.</li>
 *   <li><b>Server ends the stream normally</b> ({@code onCompleted} with no error): if this session
 *   asked for that (via {@link #endAndClose}), it is expected and the publisher completes normally
 *   ({@link SubmissionPublisher#close()}). If the server ends the call on its own with no {@code
 *   stop=true} ever sent, that is treated exactly like a transport failure (a live bidi call is never
 *   expected to complete server-side on its own) and surfaces via {@code onError} too.</li>
 *   <li><b>A malformed response</b> (e.g. a confidence/box value outside the domain's validated
 *   range): unlike {@link DetectionStreamSession}, there is no pending future to fail just for this
 *   one item — responses are unsolicited. The malformed response is logged at WARNING and dropped;
 *   the session and the publisher both keep running for the next response.</li>
 * </ul>
 *
 * <p>gRPC stream observers are not thread-safe: every write to the request observer (the lazy open,
 * {@link #start}, {@link #reconfigure}, {@link #attitude}, and the {@code stop=true} message in {@link
 * #endAndClose}) is serialized through {@link #writeLock}.
 *
 * <p><b>Teardown is idempotent</b> ({@link #torndown}, an {@link AtomicBoolean} CAS guard), same
 * discipline as {@link DetectionStreamSession}: a transport error and an explicit {@link
 * #endAndClose} racing each other resolve to exactly one teardown.
 */
final class PulledDetectionSession {

    private static final System.Logger LOG = System.getLogger(PulledDetectionSession.class.getName());

    private final StreamId streamId;
    private final InferenceGrpc.InferenceStub asyncStub;
    private final ConcurrentHashMap<StreamId, PulledDetectionSession> sessions;
    private final int detectWidth;
    private final SubmissionPublisher<DetectionResult> publisher = new SubmissionPublisher<>();
    private final Object writeLock = new Object();
    private final AtomicBoolean torndown = new AtomicBoolean(false);

    private volatile StreamObserver<PullControl> requestObserver;
    private volatile PipelineConfig currentConfig;
    private volatile CameraAttitude currentAttitude;

    /**
     * @param detectWidth the wire's {@code PullControl.detect_width} to restate on every message —
     *                     {@link GrpcCvSettings#detectWidth()}, the same downscale-target knob push
     *                     mode uses, repurposed here as "how wide the worker should downscale locally"
     */
    PulledDetectionSession(StreamId streamId, InferenceGrpc.InferenceStub asyncStub,
            ConcurrentHashMap<StreamId, PulledDetectionSession> sessions, int detectWidth) {
        this.streamId = streamId;
        this.asyncStub = asyncStub;
        this.sessions = sessions;
        this.detectWidth = detectWidth;
    }

    Flow.Publisher<DetectionResult> publisher() {
        return publisher;
    }

    /**
     * Opens the call and sends the first {@code PullControl} — the only message of this session that
     * ever carries {@code source_url} (this class never sends {@code rtsp_transport}; the wire default
     * of {@code ""} means "server default", which is all this adapter states today).
     */
    void start(URI sourceUrl, PipelineConfig config) {
        currentConfig = config;
        send(controlBuilder(false).setSourceUrl(sourceUrl.toString()).build());
    }

    /** Restates the full desired state with the new config; the last-known attitude rides along too. */
    void reconfigure(PipelineConfig config) {
        currentConfig = config;
        send(controlBuilder(false).build());
    }

    /** Restates the full desired state with the new attitude; the last-known config rides along too. */
    void attitude(CameraAttitude attitude) {
        currentAttitude = attitude;
        send(controlBuilder(false).build());
    }

    /** {@code stream_id} plus every hot field from the last-known config/attitude, {@code stop} as given. */
    private PullControl.Builder controlBuilder(boolean stop) {
        PullControl.Builder builder = PullControl.newBuilder()
                .setStreamId(streamId.value().toString())
                .setStop(stop);
        PipelineConfig config = currentConfig;
        if (config != null) {
            builder.setModelId(config.model().id())
                    .setModelVersion(config.model().version())
                    .setConfidenceThreshold((float) config.confidenceThreshold())
                    .setTargetFps((float) config.inferenceFps())
                    .setDetectWidth(detectWidth)
                    .setTracking(DetectionFrameCodec.toWireTrackingConfig(config.tracking()));
        }
        CameraAttitude attitude = currentAttitude;
        if (attitude != null && attitude.known()) {
            builder.setCameraPose(DetectionFrameCodec.toWireCameraPose(attitude));
        }
        return builder;
    }

    private void send(PullControl control) {
        if (torndown.get()) {
            return; // already failed/closed -- no point attempting a write that can only be rejected
        }
        synchronized (writeLock) {
            try {
                openIfNeeded().onNext(control);
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING,
                        () -> "Failed to send PullControl on pulled detection stream " + streamId, e);
                failAndDrop(e);
            }
        }
    }

    /**
     * Fails the publisher and drops this session so the next {@code open()} for this {@link
     * StreamId} starts fresh, exactly once (see {@link #torndown}).
     */
    private void failAndDrop(Throwable cause) {
        if (!torndown.compareAndSet(false, true)) {
            return;
        }
        sessions.remove(streamId, this);
        publisher.closeExceptionally(cause);
    }

    /** Must be called while holding {@link #writeLock}. */
    private StreamObserver<PullControl> openIfNeeded() {
        StreamObserver<PullControl> observer = requestObserver;
        if (observer == null) {
            LOG.log(System.Logger.Level.INFO, () -> "Opening pulled detection stream for " + streamId);
            observer = asyncStub.detectPulled(new ResponseHandler());
            requestObserver = observer;
        }
        return observer;
    }

    private void onResponse(DetectionResponse response) {
        DetectionResult result;
        try {
            result = DetectionFrameCodec.decode(streamId, response);
        } catch (RuntimeException e) {
            // Unsolicited response -- there is no pending future to fail for just this one item
            // (the structural difference from DetectionStreamSession, see class javadoc). Drop it
            // and keep the session running for the next response.
            LOG.log(System.Logger.Level.WARNING,
                    () -> "Malformed detection response on pulled stream " + streamId + "; dropping it", e);
            return;
        }
        // Latest-wins backpressure: never block delivery for a slow subscriber, matching
        // VideoSourcePort's own drop policy (PulledDetectionPort's class javadoc, "Backpressure").
        publisher.offer(result, (subscriber, dropped) -> true);
    }

    private void onTransportError(Throwable t) {
        LOG.log(System.Logger.Level.WARNING, () -> "Pulled detection stream for " + streamId + " failed", t);
        failAndDrop(t);
    }

    private void onServerCompleted() {
        // The server ended the call on its own, with no stop=true ever sent -- a live bidi call is
        // never expected to complete server-side on its own, so this is a failure, not a graceful
        // end. If this session's own endAndClose() already tore it down (torndown already true,
        // since we sent stop=true first), failAndDrop is a no-op here, exactly as intended.
        failAndDrop(new IllegalStateException("Pulled detection stream for " + streamId + " completed unexpectedly"));
    }

    /**
     * Sends {@code stop=true}, half-closes the request observer, and completes the publisher
     * normally (this is an expected, requested teardown -- not surfaced as an error). Idempotent.
     */
    void endAndClose() {
        if (!torndown.compareAndSet(false, true)) {
            return;
        }
        sessions.remove(streamId, this);
        synchronized (writeLock) {
            StreamObserver<PullControl> observer = requestObserver;
            if (observer != null) {
                try {
                    observer.onNext(controlBuilder(true).build());
                    observer.onCompleted();
                } catch (RuntimeException e) {
                    LOG.log(System.Logger.Level.DEBUG,
                            () -> "Ignoring error stopping pulled detection stream " + streamId, e);
                }
            }
        }
        publisher.close();
    }

    private final class ResponseHandler implements StreamObserver<DetectionResponse> {
        @Override
        public void onNext(DetectionResponse response) {
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
