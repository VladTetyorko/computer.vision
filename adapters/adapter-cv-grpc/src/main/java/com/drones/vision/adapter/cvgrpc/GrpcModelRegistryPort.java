package com.drones.vision.adapter.cvgrpc;

import com.drones.vision.domain.model.ModelRef;
import com.drones.vision.domain.port.out.ModelRegistryPort;
import com.drones.vision.proto.v1.Ack;
import com.drones.vision.proto.v1.ModelInfo;
import com.drones.vision.proto.v1.ModelList;
import com.drones.vision.proto.v1.ModelRefMsg;
import com.drones.vision.proto.v1.TrainingGrpc;
import com.google.protobuf.Empty;
import io.grpc.ManagedChannel;
import io.grpc.StatusRuntimeException;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * {@link ModelRegistryPort} over the generated {@code Training/ListModels} and
 * {@code Training/PromoteModel} gRPC RPCs — the Java client half of the
 * ingest/promote seam {@code docs/CV-TRAINING-PLAN.md} Phase 2 §7 fills (see
 * {@code proto/vision/v1/cv.proto}'s {@code Training} service).
 *
 * <h2>Channel reuse</h2>
 * This class deliberately takes a pre-built {@link ManagedChannel} rather than
 * building its own from a host/port, and never shuts it down. The intent (per
 * {@code docs/CV-TRAINING-PLAN.md} Phase 2 §7) is that the caller — {@code
 * vision-app}'s wiring — hands it the <em>same</em> channel instance {@link
 * GrpcDetectionPort} already uses for {@code Inference/DetectStream}, so the
 * platform holds exactly one connection to cv-service, not two independently
 * configured ones. Channel lifecycle (construction with keepalive tuning,
 * shutdown) stays wherever the channel was built — see {@link
 * GrpcDetectionPort}'s host/port constructor for that setup, which this class
 * does not duplicate.
 *
 * <h2>Failure semantics</h2>
 * Both RPCs here are blocking unary calls issued with a {@value
 * #CALL_TIMEOUT_SECONDS}s deadline (control-plane operations, not per-frame —
 * so a looser bound than {@link GrpcCvSettings#RESPONSE_TIMEOUT_SECONDS}
 * is fine) so an unreachable or hung cv-service fails the calling thread
 * within a bounded time instead of blocking it indefinitely.
 * <ul>
 *   <li><b>Transport failure or deadline exceeded</b>: propagates the raw
 *   {@link StatusRuntimeException} to the caller, exactly like {@link
 *   GrpcDetectionPort#detect}'s transport-failure path — this class never
 *   swallows an unreachable cv-service into an empty list, since a caller
 *   cannot otherwise tell "no models exist" from "couldn't ask." {@link
 *   #models()} only returns an empty list when cv-service actually answers
 *   with an empty roster.</li>
 *   <li><b>{@link #promote(ModelRef)} explicitly refused</b> ({@code
 *   Ack.ok=false}): throws {@link IllegalStateException} naming the refusal
 *   reason — the same "reachable but refused" convention {@code
 *   FlightCommandPort}'s implementations use for a vehicle's own command
 *   refusal.</li>
 *   <li><b>Malformed {@link ModelInfo}</b> (blank {@code id}/{@code
 *   version}): {@link ModelRef}'s own compact constructor throws {@link
 *   IllegalArgumentException}, failing the whole {@link #models()} call
 *   rather than silently dropping the bad entry — a malformed roster entry
 *   is a cv-service bug worth surfacing, not hiding.</li>
 * </ul>
 *
 * <h2>{@code ModelInfo.stage}/{@code metrics}</h2>
 * {@link ModelRef} models only {@code (id, version)}. The wire {@code
 * ModelInfo}'s {@code stage} ({@code "active"} vs. {@code "available"}) is
 * surfaced through {@link #active()} — the single reference cv-service reports
 * as the current default — rather than by widening {@code ModelRef} (being the
 * active default is a registry fact, not a property of a reference used
 * throughout detection/pipeline config). {@code ModelInfo.metrics} has no home
 * on this port and is dropped; {@code docs/CV-TRAINING-PLAN.md} §8's planned
 * {@code GET /api/cv/registry/models} REST surface can carry it via a
 * wire-level passthrough if a later wave needs it.
 *
 * <h2>Threading</h2>
 * Plain class, no Spring. Both methods block the calling thread for the
 * duration of one RPC (bounded by {@value #CALL_TIMEOUT_SECONDS}s), matching
 * {@link ModelRegistryPort}'s synchronous contract; safe for concurrent use
 * from multiple threads (the underlying {@link ManagedChannel} and blocking
 * stub already are).
 */
public final class GrpcModelRegistryPort implements ModelRegistryPort {

    private static final System.Logger LOG = System.getLogger(GrpcModelRegistryPort.class.getName());

    /**
     * Per-call deadline for both RPCs. Registry queries/promotions are
     * infrequent control-plane operations (not the per-frame detection hot
     * path), so this is looser than {@link
     * GrpcCvSettings#RESPONSE_TIMEOUT_SECONDS} — the point is only to
     * guarantee the calling thread is never blocked indefinitely by an
     * unreachable or hung cv-service.
     */
    static final long CALL_TIMEOUT_SECONDS = 10;

    private final TrainingGrpc.TrainingBlockingStub stub;
    private final long callTimeoutSeconds;

    /**
     * @param channel a channel to cv-service, typically the same one {@link
     *                GrpcDetectionPort} uses (see class javadoc's "Channel
     *                reuse"). Never closed by this class.
     */
    public GrpcModelRegistryPort(ManagedChannel channel) {
        this(channel, Duration.ofSeconds(CALL_TIMEOUT_SECONDS));
    }

    /**
     * @param callTimeout per-call deadline for both RPCs — {@code vision.cv.registry.call-timeout}
     *                    (docs/LAYERING-REFACTOR-PLAN.md wave F4), replacing this class's own {@link
     *                    #CALL_TIMEOUT_SECONDS} constant as the actual value used.
     */
    public GrpcModelRegistryPort(ManagedChannel channel, Duration callTimeout) {
        Objects.requireNonNull(channel, "channel must not be null");
        this.callTimeoutSeconds = Objects.requireNonNull(callTimeout, "callTimeout must not be null").toSeconds();
        this.stub = TrainingGrpc.newBlockingStub(channel);
    }

    @Override
    public List<ModelRef> models() {
        ModelList response;
        try {
            response = blockingStub().listModels(Empty.getDefaultInstance());
        } catch (StatusRuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "Failed to list CV models from cv-service", e);
            throw e;
        }
        return response.getModelsList().stream()
                .map(GrpcModelRegistryPort::toModelRef)
                .toList();
    }

    /** The stage cv-service marks the current default with (see {@code cv-service} {@code ListModels}). */
    private static final String ACTIVE_STAGE = "active";

    @Override
    public Optional<ModelRef> active() {
        ModelList response;
        try {
            response = blockingStub().listModels(Empty.getDefaultInstance());
        } catch (StatusRuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "Failed to list CV models from cv-service", e);
            throw e;
        }
        return response.getModelsList().stream()
                .filter(info -> ACTIVE_STAGE.equals(info.getStage()))
                .findFirst()
                .map(GrpcModelRegistryPort::toModelRef);
    }

    @Override
    public void promote(ModelRef ref) {
        Objects.requireNonNull(ref, "ref must not be null");
        ModelRefMsg request = ModelRefMsg.newBuilder()
                .setId(ref.id())
                .setVersion(ref.version())
                .build();

        Ack ack;
        try {
            ack = blockingStub().promoteModel(request);
        } catch (StatusRuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, () -> "Failed to promote CV model " + ref + " on cv-service", e);
            throw e;
        }

        if (!ack.getOk()) {
            LOG.log(System.Logger.Level.WARNING,
                    () -> "cv-service refused to promote model " + ref + ": " + ack.getMessage());
            throw new IllegalStateException("cv-service refused to promote model " + ref + ": " + ack.getMessage());
        }
        LOG.log(System.Logger.Level.INFO, () -> "Promoted CV model " + ref);
    }

    private TrainingGrpc.TrainingBlockingStub blockingStub() {
        return stub.withDeadlineAfter(callTimeoutSeconds, TimeUnit.SECONDS);
    }

    private static ModelRef toModelRef(ModelInfo info) {
        return new ModelRef(info.getId(), info.getVersion());
    }
}
