package com.drones.vision.adapter.cvgrpc;

import com.drones.vision.domain.model.CameraAttitude;
import com.drones.vision.domain.model.DetectionResult;
import com.drones.vision.domain.model.PipelineConfig;
import com.drones.vision.domain.model.StreamId;
import com.drones.vision.domain.port.out.PulledDetectionPort;
import com.drones.vision.proto.v1.InferenceGrpc;
import io.grpc.ManagedChannel;

import java.net.URI;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Flow;

/**
 * {@link PulledDetectionPort} over the generated {@code Inference/DetectPulled} bidi stub — the Java
 * client half of the pull-mode contract added by docs/plans/active/MEDIA-SOT-PLAN.md wave M1 (see
 * {@code proto/vision/v1/cv.proto}).
 *
 * <h2>Channel sharing</h2>
 * This class takes a pre-built {@link ManagedChannel} rather than owning a {@code (host, port)}
 * constructor of its own — the same "shared channel, never closed here" contract {@link
 * GrpcModelRegistryPort}/{@link GrpcTrainingPort}/{@link GrpcDatasetUploadPort} already follow (see
 * each of their own javadoc): {@code DetectPulled} is a second RPC on the same {@code Inference}
 * service {@link GrpcDetectionPort#detect} already talks to, so wiring is expected to hand this class
 * the identical channel {@link GrpcDetectionPort} built, not stand up a second TCP connection to the
 * same cv-service. Channel lifecycle stays wherever the channel was built.
 *
 * <p>Unlike {@link GrpcDetectionPort}, this class delegates every frame-payload/wire-tuning concern to
 * cv-service itself — there is no frame to shrink or JPEG-encode here at all, since the worker pulls
 * and decodes frames on its own (that is the entire point of pull mode). The only per-stream state
 * this class needs is a {@link PulledDetectionSession}; see that class for the bidi call machinery.
 *
 * <h2>Reconnect/backoff is deliberately not built here</h2>
 * docs/plans/active/MEDIA-SOT-PLAN.md decision D5: a source-unopenable or stalled-past-timeout pull ends its
 * call with {@code UNAVAILABLE}, which this class surfaces honestly via {@link Flow.Subscriber#onError}
 * and nothing more. Reopening with backoff is the existing generic {@code
 * SupervisedPublisher<DetectionResult>}'s job (vision-application, a later wiring wave), reusing the
 * exact same resilience machinery a {@code VideoSourcePort} already gets.
 */
public final class GrpcPulledDetectionPort implements PulledDetectionPort {

    private final InferenceGrpc.InferenceStub asyncStub;
    private final int detectWidth;
    private final ConcurrentHashMap<StreamId, PulledDetectionSession> sessions = new ConcurrentHashMap<>();

    /**
     * @param channel  a channel already open to cv-service — typically the same one {@link
     *                 GrpcDetectionPort} built; never closed by this class
     * @param settings supplies {@link GrpcCvSettings#detectWidth()}, restated on every {@code
     *                 PullControl} as {@code detect_width}
     * @throws NullPointerException if either argument is {@code null}
     */
    public GrpcPulledDetectionPort(ManagedChannel channel, GrpcCvSettings settings) {
        Objects.requireNonNull(channel, "channel must not be null");
        Objects.requireNonNull(settings, "settings must not be null");
        this.asyncStub = InferenceGrpc.newStub(channel);
        this.detectWidth = settings.detectWidth();
    }

    @Override
    public Flow.Publisher<DetectionResult> open(StreamId id, URI sourceUrl, PipelineConfig config) {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(sourceUrl, "sourceUrl must not be null");
        Objects.requireNonNull(config, "config must not be null");

        PulledDetectionSession session = new PulledDetectionSession(id, asyncStub, sessions, detectWidth);
        PulledDetectionSession previous = sessions.put(id, session);
        if (previous != null) {
            previous.endAndClose(); // defensive: an id must not have two live pulls (mirrors FfmpegVideoSource)
        }
        session.start(sourceUrl, config);
        return session.publisher();
    }

    @Override
    public void reconfigure(StreamId id, PipelineConfig config) {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(config, "config must not be null");
        PulledDetectionSession session = sessions.get(id);
        if (session != null) {
            session.reconfigure(config);
        }
    }

    @Override
    public void attitude(StreamId id, CameraAttitude attitude) {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(attitude, "attitude must not be null");
        PulledDetectionSession session = sessions.get(id);
        if (session != null) {
            session.attitude(attitude);
        }
    }

    @Override
    public void close(StreamId id) {
        Objects.requireNonNull(id, "id must not be null");
        PulledDetectionSession session = sessions.get(id);
        if (session != null) {
            session.endAndClose();
        }
    }
}
