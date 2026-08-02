package com.drones.vision.adapter.cvgrpc;

import com.drones.vision.domain.model.JobState;
import com.drones.vision.domain.model.TrainingJobSpec;
import com.drones.vision.domain.model.TrainingProgress;
import com.drones.vision.domain.port.out.TrainingPort;
import com.drones.vision.proto.v1.TrainingGrpc;
import io.grpc.ManagedChannel;
import io.grpc.StatusRuntimeException;

import java.util.Iterator;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * {@link TrainingPort} over the generated {@code Training/StartTraining} server-streaming gRPC RPC
 * — the Java client half of the (optional, GPU-training-host-only) training seam
 * {@code docs/CV-TRAINING-PLAN.md} Phase 2 §7 describes (see {@code proto/vision/v1/cv.proto}'s
 * {@code Training} service). Never wired against the GB4005 inference box; production GB4005
 * deployments leave {@code Training/StartTraining} {@code UNIMPLEMENTED} (cv-service side).
 *
 * <h2>Channel reuse</h2>
 * Like {@link GrpcModelRegistryPort}, this class takes a pre-built {@link ManagedChannel} rather
 * than a host/port pair and never shuts it down — the intent is that {@code vision-app}'s wiring
 * hands it the <em>same</em> channel {@link GrpcDetectionPort} and {@link GrpcModelRegistryPort}
 * already share, so the platform holds exactly one connection to a training host, not a third
 * independently-configured one. Channel lifecycle stays wherever the channel was built.
 *
 * <h2>No per-call deadline — deliberately unlike {@link GrpcModelRegistryPort}</h2>
 * {@link GrpcModelRegistryPort}'s RPCs are quick control-plane calls, so a {@value
 * GrpcModelRegistryPort#CALL_TIMEOUT_SECONDS}s deadline bounds them safely. Training is the
 * opposite: a single job can run for many epochs, each potentially minutes long, over one
 * long-lived streaming call — a short (or even a generous fixed) deadline would kill every real
 * job partway through. {@link #startTraining} therefore issues the RPC with no deadline at all and
 * instead relies entirely on the shared channel's own HTTP/2 keepalive tuning ({@link
 * GrpcCvSettings}'s {@code KEEPALIVE_TIME_SECONDS}/{@code KEEPALIVE_TIMEOUT_SECONDS}/{@code
 * KEEPALIVE_WITHOUT_CALLS}, applied by {@link GrpcDetectionPort}'s host/port constructor that builds
 * the shared channel) to
 * detect a genuinely dead connection — a real server crash or network partition ends the call via
 * a transport error within roughly one keepalive cycle, while a merely slow epoch never trips a
 * deadline that was never armed.
 *
 * <h2>Cancellation</h2>
 * This port exposes no explicit cancel handle — {@link TrainingPort}'s contract is that a caller
 * cancels a run in progress by interrupting the thread executing {@link #startTraining} (per its
 * own Threading contract, that thread belongs to the caller's executor, never this method). No
 * extra code is needed here to honor that: the generated blocking stub's iterator already reacts to
 * thread interruption on its own — {@code io.grpc.stub.ClientCalls.BlockingResponseStream
 * .waitForNext()} catches {@link InterruptedException} while blocked between messages, cancels the
 * underlying {@code ClientCall}, restores the thread's interrupt flag, and then surfaces the
 * resulting {@link StatusRuntimeException} (status {@code CANCELLED}) out of the next {@code
 * hasNext()}/{@code next()} call. That exception propagates out of {@link #startTraining} exactly
 * like any other transport failure (see below) — consistent with {@link TrainingPort}'s own
 * contract that an unrecoverable transport-level end "surfaces as a thrown exception, not a
 * synthesized terminal progress."
 *
 * <h2>Failure semantics</h2>
 * <ul>
 *   <li><b>Transport failure, including client-initiated cancellation</b> (the server process
 *   restarting, the connection dying, or the calling thread being interrupted as above): the raw
 *   {@link StatusRuntimeException} propagates out of {@link #startTraining} — this class never
 *   swallows a broken stream into a quiet early return.</li>
 *   <li><b>The server ends the stream with no terminal {@link JobState#SUCCEEDED}/{@link
 *   JobState#FAILED} message</b> (cv-service's own documented cancellation behavior): {@code
 *   hasNext()} simply returns {@code false} and {@link #startTraining} returns normally, having
 *   already delivered every {@code RUNNING} message it saw to {@code onProgress}. This is not an
 *   error — it is exactly {@link TrainingPort}'s documented "cancellation ends the stream with no
 *   terminal message" case, and no synthetic terminal {@link TrainingProgress} is invented to paper
 *   over it.</li>
 *   <li><b>Malformed {@link TrainingProgress}</b> (e.g. a blank {@code job_id} from cv-service):
 *   the domain record's own compact constructor throws {@link IllegalArgumentException}, which
 *   propagates out of {@link #startTraining} uncaught — a malformed wire message is treated as a
 *   cv-service bug worth surfacing loudly, the same posture {@link GrpcModelRegistryPort} takes for
 *   a malformed {@code ModelInfo} entry, not a per-message failure to silently skip.</li>
 * </ul>
 *
 * <h2>{@code JobState.JOB_STATE_UNSPECIFIED}</h2>
 * Mapped defensively to {@link JobState#RUNNING} (with a logged warning), same for the generated
 * {@code UNRECOGNIZED} constant (an enum value the wire sent that this build's proto doesn't know
 * about). Treating either as terminal ({@code SUCCEEDED}/{@code FAILED}) would risk a caller
 * concluding a job finished when it did not; treating it as still-running is the conservative
 * choice — worst case, a caller keeps waiting one message longer than strictly necessary.
 *
 * <h2>Threading</h2>
 * Plain class, no Spring. {@link #startTraining} blocks the calling thread for the entire job —
 * see {@link TrainingPort}'s own Threading contract for why that is the caller's responsibility,
 * not this class's. Safe to call concurrently for different jobs (the underlying {@link
 * ManagedChannel} and blocking stub already are); a single call is not meant to be invoked
 * concurrently for the same job.
 */
public final class GrpcTrainingPort implements TrainingPort {

    private static final System.Logger LOG = System.getLogger(GrpcTrainingPort.class.getName());

    private final TrainingGrpc.TrainingBlockingStub stub;

    /**
     * @param channel a channel to a training host, typically the same one {@link
     *                GrpcDetectionPort}/{@link GrpcModelRegistryPort} use (see class javadoc's
     *                "Channel reuse"). Never closed by this class.
     */
    public GrpcTrainingPort(ManagedChannel channel) {
        Objects.requireNonNull(channel, "channel must not be null");
        this.stub = TrainingGrpc.newBlockingStub(channel);
    }

    @Override
    public void startTraining(TrainingJobSpec spec, Consumer<TrainingProgress> onProgress) {
        Objects.requireNonNull(spec, "spec must not be null");
        Objects.requireNonNull(onProgress, "onProgress must not be null");

        com.drones.vision.proto.v1.TrainingJobSpec request = com.drones.vision.proto.v1.TrainingJobSpec.newBuilder()
                .setBaseModel(spec.baseModel())
                .setDatasetId(spec.datasetId())
                .setEpochs(spec.epochs())
                .build();

        LOG.log(System.Logger.Level.INFO, () -> "Starting CV training job: baseModel=" + spec.baseModel()
                + ", datasetId=" + spec.datasetId() + ", epochs=" + spec.epochs());

        Iterator<com.drones.vision.proto.v1.TrainingProgress> stream;
        try {
            // No deadline: see class javadoc "No per-call deadline". This calling thread's own
            // interruption (a caller-initiated cancel) is handled by the generated iterator, not
            // by anything here — see class javadoc "Cancellation".
            stream = stub.startTraining(request);
        } catch (StatusRuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "Failed to start CV training job", e);
            throw e;
        }

        try {
            while (stream.hasNext()) {
                com.drones.vision.proto.v1.TrainingProgress wire = stream.next();
                onProgress.accept(toDomainProgress(wire));
            }
        } catch (StatusRuntimeException e) {
            LOG.log(System.Logger.Level.WARNING,
                    () -> "CV training stream ended with a transport error (baseModel=" + spec.baseModel()
                            + ", datasetId=" + spec.datasetId() + "): " + e.getStatus(),
                    e);
            throw e;
        }

        LOG.log(System.Logger.Level.INFO, () -> "CV training stream ended: baseModel=" + spec.baseModel()
                + ", datasetId=" + spec.datasetId());
    }

    private static TrainingProgress toDomainProgress(com.drones.vision.proto.v1.TrainingProgress wire) {
        return new TrainingProgress(
                wire.getJobId(),
                wire.getEpoch(),
                wire.getTotalEpochs(),
                wire.getLoss(),
                wire.getMap50(),
                toDomainState(wire.getState()),
                wire.getMessage());
    }

    private static JobState toDomainState(com.drones.vision.proto.v1.JobState wire) {
        return switch (wire) {
            case RUNNING -> JobState.RUNNING;
            case SUCCEEDED -> JobState.SUCCEEDED;
            case FAILED -> JobState.FAILED;
            case JOB_STATE_UNSPECIFIED, UNRECOGNIZED -> {
                LOG.log(System.Logger.Level.WARNING,
                        () -> "cv-service sent a JobState this client doesn't recognize (" + wire
                                + "); treating as RUNNING defensively — see class javadoc");
                yield JobState.RUNNING;
            }
        };
    }
}
