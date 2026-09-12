package com.drones.vision.adapter.cvgrpc;

import com.drones.vision.proto.v1.InferenceGrpc;
import com.drones.vision.proto.v1.InspectRequest;
import com.drones.vision.proto.v1.InspectResponse;
import io.grpc.ManagedChannel;
import io.grpc.StatusRuntimeException;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * {@code Inference/Inspect} over the generated gRPC stub (docs/plans/active/CV-ORCHESTRATION-PLAN.md
 * &sect;4.9) — cv-service's own process-wide facts ({@code ProcessFacts}: detector client kind,
 * live session count, gate permits, ledger ring depth, the stream ids it is serving), read by
 * {@link CvStatusProvider} to fold capacity facts into {@code GET /api/system/status}'s cv row.
 * Deliberately <b>not</b> a domain-shaped port: this is adapter-internal enrichment of a status
 * sentence, not a capability any context module's application layer consumes — {@code
 * StreamPipeline}/{@code DefaultStreamService} never call this class.
 *
 * <h2>Channel reuse</h2>
 * Takes a pre-built {@link ManagedChannel}, never shuts it down — same convention as {@link
 * GrpcModelRegistryPort} (see that class's own javadoc's "Channel reuse" section); typically the
 * same channel {@link GrpcDetectionPort} streams frames over, since {@code Inspect} lives on the
 * same {@code Inference} service as {@code DetectStream}/{@code DetectPulled}.
 *
 * <h2>Failure semantics</h2>
 * {@link #processFacts()} propagates a raw {@link StatusRuntimeException} on transport failure or
 * deadline exceeded, exactly like {@link GrpcModelRegistryPort}'s RPCs — {@link CvStatusProvider}
 * is the one place that catches it, since "Inspect failed" is itself a status fact (reported as a
 * plain, capacity-free detail sentence), not a fault this class should silently paper over.
 *
 * <h2>Threading</h2>
 * Plain class, no Spring. Blocks the calling thread for the duration of one RPC (bounded by {@link
 * #CALL_TIMEOUT_SECONDS}); safe for concurrent use from multiple threads, matching the underlying
 * {@link ManagedChannel} and blocking stub.
 */
public final class GrpcCvInspectClient {

    /**
     * Per-call deadline. {@code Inspect} is explicitly documented as cheap on the cv-service side
     * (proto's own comment: "Cheap enough to answer while streams are running") and this class only
     * ever asks for process-wide facts ({@link #processFacts()}, empty {@code stream_id}, {@code
     * frames=0} — no ledger history), so a short, fixed deadline is enough; looser than {@link
     * GrpcCvSettings#RESPONSE_TIMEOUT_SECONDS} would need to be for a per-frame call, tighter than
     * {@link GrpcModelRegistryPort#CALL_TIMEOUT_SECONDS} since a status poll must not itself become
     * the slow part of {@code GET /api/system/status}.
     */
    static final long CALL_TIMEOUT_SECONDS = 5;

    private final InferenceGrpc.InferenceBlockingStub stub;
    private final long callTimeoutSeconds;

    /** @param channel a channel to cv-service, typically the same one {@link GrpcDetectionPort} uses. Never closed by this class. */
    public GrpcCvInspectClient(ManagedChannel channel) {
        this(channel, Duration.ofSeconds(CALL_TIMEOUT_SECONDS));
    }

    public GrpcCvInspectClient(ManagedChannel channel, Duration callTimeout) {
        Objects.requireNonNull(channel, "channel must not be null");
        this.callTimeoutSeconds = Objects.requireNonNull(callTimeout, "callTimeout must not be null").toSeconds();
        this.stub = InferenceGrpc.newBlockingStub(channel);
    }

    /**
     * Process-wide facts: empty {@code stream_id}, {@code frames=0} — per {@code InspectRequest}'s
     * own contract, that combination means "the process and its session list," never one session's
     * ledger history (this class has no reader for {@code InspectResponse.ledgers}/{@code .session}
     * today; {@link #inspect} exposes the raw response for a future caller that needs those).
     *
     * @return cv-service's own {@code InspectResponse.process} — caller decides what to do with a transport failure
     */
    public InspectResponse processFacts() {
        return inspect("", 0);
    }

    /**
     * @param streamId empty for process-wide facts; a specific stream id for that session's own
     *                 facts plus its ledger history
     * @param frames   how many of that session's most recent {@code FrameLedger}s to include, newest
     *                 last; {@code 0} means the whole ring. Ignored when {@code streamId} is empty
     * @return the raw {@code InspectResponse} — {@code found=false} means a named {@code streamId}
     *         has no live session
     */
    public InspectResponse inspect(String streamId, int frames) {
        Objects.requireNonNull(streamId, "streamId must not be null");
        InspectRequest request = InspectRequest.newBuilder().setStreamId(streamId).setFrames(frames).build();
        return stub.withDeadlineAfter(callTimeoutSeconds, TimeUnit.SECONDS).inspect(request);
    }
}
