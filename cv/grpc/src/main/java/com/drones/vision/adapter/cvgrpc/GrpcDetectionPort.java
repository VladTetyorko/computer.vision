package com.drones.vision.adapter.cvgrpc;

import com.drones.vision.perception.domain.model.CameraAttitude;
import com.drones.vision.perception.domain.model.DetectionResult;
import com.drones.vision.perception.domain.model.PipelineConfig;
import com.drones.vision.kernel.StreamId;
import com.drones.vision.perception.domain.model.VideoFrame;
import com.drones.vision.perception.domain.port.DetectionPort;
import com.drones.vision.proto.v1.FrameRequest;
import com.drones.vision.proto.v1.InferenceGrpc;
import io.grpc.ManagedChannel;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * {@link DetectionPort} over the generated {@code Inference/DetectStream} gRPC stub — the Java
 * client half of the Java&harr;Python CV service contract (see {@code proto/vision/v1/cv.proto}).
 *
 * <p>This class is the {@code DetectionPort} entry point only; the two collaborators it delegates to
 * carry the interesting logic:
 * <ul>
 *   <li>{@link DetectionFrameCodec} — pure wire&harr;domain conversion for one frame/response at a
 *   time, including the wide-{@code BGR24} downscale+JPEG-encode payload-shrinking path.</li>
 *   <li>{@link DetectionStreamSession} — one open bidi call per {@link StreamId}, correlation by
 *   frame sequence, and all transport-failure/timeout/teardown handling.</li>
 * </ul>
 *
 * <h2>Stream lifecycle</h2>
 * There is no idle eviction. Callers that know a stream has ended should call
 * {@link #streamEnded(StreamId)} to fail any still-pending futures for it and half-close its request
 * observer; skipping it just leaves the call open until {@link #close()} (the next {@code detect()}
 * for that id would simply keep reusing it). {@link #close()} shuts down every open stream and then
 * the underlying channel, whether the channel was built by this instance or supplied by the caller.
 *
 * <h2>Threading</h2>
 * Plain class, no Spring. Looking up/opening the stream and sending the request are non-blocking;
 * the returned stage completes later, on a gRPC executor thread. <b>One nuance to the "never blocks"
 * story</b>: {@link DetectionFrameCodec#encode}'s downscale-and-JPEG-encode step (when it applies)
 * runs synchronously on the calling thread, inside {@link #detect}, before the request is even sent —
 * it is CPU-bound work (no I/O), bounded at roughly 15ms for a 720p frame, and it *replaces*
 * serializing/transmitting several megabytes of raw pixel data with encoding and sending a couple
 * hundred KB, so net caller-thread cost versus the pre-existing fast path is roughly flat rather than
 * new added latency. A conversion failure fails only that one frame's returned stage — see {@link
 * DetectionFrameCodec}'s class javadoc.
 *
 * <h2>Connectivity gate</h2>
 * When constructed with a {@link CvChannelSupervisor} (docs/plans/active/CV-RECONNECT-PLAN.md), {@link
 * #detect} checks {@link CvChannelSupervisor#available()} before doing anything else — before {@link
 * DetectionFrameCodec#encode} and before touching {@link #sessions} — and fails fast with a stackless
 * {@link CvUnavailableException} while the gate is closed. Without a supervisor (the two-arg
 * constructor), there is no gate at all: every existing call site and test keeps today's exact
 * behaviour.
 */
public final class GrpcDetectionPort implements DetectionPort, AutoCloseable {

    private final ManagedChannel channel;
    private final InferenceGrpc.InferenceStub asyncStub;
    private final GrpcCvSettings settings;
    private final DetectionFrameCodec codec;
    private final CvChannelSupervisor supervisor;
    private final ConcurrentHashMap<StreamId, DetectionStreamSession> sessions = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean(false);

    /**
     * Builds a {@link ManagedChannel} to {@code host:port} per {@code settings} (plaintext-or-not,
     * HTTP/2 keepalive tuning — the CV service is reached over a private/internal network:
     * docker-compose, or a Wi-Fi/VPN link to a remote GPU box, see {@code docs/plans/done/REMOTE-CV-PLAN.md} —
     * so {@link GrpcCvSettings#plaintext()} defaults to {@code true}) and delegates to the canonical
     * constructor.
     */
    public GrpcDetectionPort(String host, int port, GrpcCvSettings settings) {
        this(buildChannel(host, port, settings), settings);
    }

    /**
     * Bring your own channel (e.g. an in-process channel in tests, or a channel shared with {@code
     * GrpcModelRegistryPort}/{@code GrpcTrainingPort}/{@code GrpcDatasetUploadPort}) plus the
     * wire-tuning/timeout settings this port needs. {@link #close()} shuts this channel down
     * regardless of who built it. Delegates to the three-arg constructor with a {@code null}
     * supervisor — <b>no connectivity gate</b>, so {@link #detect} behaves exactly as it always has;
     * every existing test and call site keeps this constructor's behaviour byte-identical.
     *
     * @throws NullPointerException if {@code channel} or {@code settings} is {@code null}
     */
    public GrpcDetectionPort(ManagedChannel channel, GrpcCvSettings settings) {
        this(channel, settings, null);
    }

    /**
     * Same as the two-arg constructor, plus a {@link CvChannelSupervisor} that gates {@link #detect}
     * (see this class's "Connectivity gate" section above). The supervisor is expected to watch the
     * <em>same</em> {@code channel} passed here — {@code vision-app}'s wiring builds one supervisor
     * per shared cv-service channel and hands it to every port constructed against that channel.
     * This constructor does not call {@link CvChannelSupervisor#start()} — the caller owns that, the
     * same way channel/supervisor lifecycle in general stays with whoever built them.
     *
     * @throws NullPointerException if {@code channel} or {@code settings} is {@code null}
     */
    public GrpcDetectionPort(ManagedChannel channel, GrpcCvSettings settings, CvChannelSupervisor supervisor) {
        this.channel = Objects.requireNonNull(channel, "channel must not be null");
        this.settings = Objects.requireNonNull(settings, "settings must not be null");
        this.supervisor = supervisor; // nullable -- null means no gate, see two-arg constructor
        this.asyncStub = InferenceGrpc.newStub(channel);
        // Resolved from the channel rather than from a host string so that every constructor gets the
        // same answer, including the shared-channel ones that never see a host. An in-process
        // channel (tests) has a non-loopback authority and so resolves to JPEG -- the behaviour
        // every existing test was written against.
        this.codec = new DetectionFrameCodec(settings.detectWidth(), settings.jpegQuality(),
                settings.wireFormat().resolve(channel.authority()));
    }

    private static ManagedChannel buildChannel(String host, int port, GrpcCvSettings settings) {
        Objects.requireNonNull(settings, "settings must not be null");
        return CvChannels.forTarget(new CvTarget(host, port), settings);
    }

    @Override
    public CompletionStage<DetectionResult> detect(VideoFrame frame, PipelineConfig config) {
        return detect(frame, config, null);
    }

    @Override
    public CompletionStage<DetectionResult> detect(VideoFrame frame, PipelineConfig config,
                                                    CameraAttitude attitude) {
        Objects.requireNonNull(frame, "frame must not be null");
        Objects.requireNonNull(config, "config must not be null");

        if (closed.get()) {
            return CompletableFuture.failedFuture(new IllegalStateException("GrpcDetectionPort is closed"));
        }

        // Gate precedes everything else -- including encode() -- deliberately. Encoding is a
        // downscale + JPEG re-encode per sampled frame (DetectionFrameCodec#encode); spending that
        // CPU on a frame this call is about to reject anyway is pure waste. It also precedes
        // sessions.computeIfAbsent() so no DetectionStreamSession/gRPC call is ever opened while
        // cv-service is known to be unreachable.
        if (supervisor != null && !supervisor.available()) {
            return CompletableFuture.failedFuture(new CvUnavailableException(supervisor.describe()));
        }

        FrameRequest request;
        try {
            request = codec.encode(frame, config, attitude);
        } catch (IOException | RuntimeException e) {
            // Conversion (downscale/JPEG-encode) failure: fails only this frame's stage, exactly
            // like a malformed response does for one pending future -- no session is created or
            // touched here, so an already-open session for this stream (if any) is unaffected.
            return CompletableFuture.failedFuture(e);
        }

        DetectionStreamSession session = sessions.computeIfAbsent(frame.streamId(), this::newSession);
        return session.send(frame.sequence(), request);
    }

    private DetectionStreamSession newSession(StreamId streamId) {
        return new DetectionStreamSession(streamId, asyncStub, sessions, settings.responseTimeout().toMillis());
    }

    /**
     * Signals that {@code streamId} has ended: fails any still-pending futures for it with a {@link
     * java.util.concurrent.CancellationException}, half-closes its request observer (best-effort),
     * and drops the stream entry so a future {@link #detect} for the same id starts a fresh call.
     * Idempotent — a second call (or one for an id with no open stream) is a no-op.
     */
    public void streamEnded(StreamId streamId) {
        Objects.requireNonNull(streamId, "streamId must not be null");
        DetectionStreamSession session = sessions.get(streamId);
        if (session != null) {
            session.endAndClose();
        }
    }

    /**
     * Idempotent. Ends every open stream (see {@link #streamEnded}) and then shuts down the channel,
     * awaiting termination for {@link GrpcCvSettings#channelShutdownTimeout()} before forcing it.
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        List<DetectionStreamSession> openSessions = new ArrayList<>(sessions.values());
        openSessions.forEach(DetectionStreamSession::endAndClose);

        channel.shutdown();
        try {
            if (!channel.awaitTermination(settings.channelShutdownTimeout().toMillis(), TimeUnit.MILLISECONDS)) {
                channel.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            channel.shutdownNow();
        }
    }
}
