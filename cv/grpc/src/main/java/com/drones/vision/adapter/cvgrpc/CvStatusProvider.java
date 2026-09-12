package com.drones.vision.adapter.cvgrpc;

import com.drones.vision.platform.Health;
import com.drones.vision.platform.SubsystemStatus;
import com.drones.vision.platform.SubsystemStatusPort;
import com.drones.vision.proto.v1.ProcessFacts;
import io.grpc.StatusRuntimeException;
import java.time.Instant;
import java.util.function.Supplier;

/**
 * {@code cv-service}'s {@link SubsystemStatusPort} (docs/plans/done/SYSTEM-STATUS-PLAN.md §4.2):
 * reports whether the shared gRPC channel to cv-service is currently reachable, using {@link
 * CvChannelSupervisor} — the same object {@code GrpcDetectionPort} already asks before every
 * detection call.
 *
 * <p>Takes a {@code Supplier<CvChannelSupervisor>} rather than the supervisor itself because {@code
 * vision-app}'s own {@code CvChannelSupervisor} bean is conditional on a narrower expression than
 * this provider is wired under (docs/plans/active/CV-RECONNECT-PLAN.md §3.3's {@code
 * vision.cv.reconnect.enabled} on top of the channel-enabling flags) — the supplier can return
 * {@code null} for that one edge case (channel wired, reconnect supervision specifically turned
 * off), which is reported as {@link Health#UNKNOWN} rather than crashing or silently pretending the
 * subsystem is off. {@code vision-app} passes {@code ObjectProvider::getIfAvailable} for the
 * supplier.
 *
 * <h2>Capacity fields (docs/plans/active/CV-ORCHESTRATION-PLAN.md §4.9, wave W2)</h2>
 * {@code inspect} is a second, independently-conditional {@code Supplier} for the same reason
 * {@code supervisor} is: {@link GrpcCvInspectClient} rides the same channel but nothing here should
 * force it to exist just to report reachability. When present and reachable, its {@link
 * GrpcCvInspectClient#processFacts()} appends detector-client kind, live session count, gate permit
 * count and stream count onto the OK detail sentence — <b>folded into the existing free-text {@code
 * detail} string, not new structured fields on {@link SubsystemStatus}</b>. {@link SubsystemStatus}
 * is a generic cross-cutting record shared by every subsystem provider (MAVLink, HLS publish, CV);
 * widening it with CV-specific fields would leak this one subsystem's shape into a type every other
 * provider also implements, for a UI that (per §4.8) already reads status as "one honest sentence."
 * A failed/absent Inspect call degrades to the channel-reachability sentence alone — capacity is an
 * enrichment, never a precondition for reporting whether cv-service is up.
 */
public final class CvStatusProvider implements SubsystemStatusPort {

    private final Supplier<CvChannelSupervisor> supervisor;
    private final Supplier<GrpcCvInspectClient> inspect;

    public CvStatusProvider(Supplier<CvChannelSupervisor> supervisor, Supplier<GrpcCvInspectClient> inspect) {
        this.supervisor = supervisor;
        this.inspect = inspect;
    }

    @Override
    public SubsystemStatus status() {
        CvChannelSupervisor cvChannelSupervisor = supervisor.get();
        if (cvChannelSupervisor == null) {
            return new SubsystemStatus("cv-service", "CV inference", Health.UNKNOWN,
                    "CV reconnect supervision is disabled (vision.cv.reconnect.enabled=false); channel "
                            + "state is not tracked", null, null);
        }
        if (cvChannelSupervisor.available()) {
            String detail = "cv-service channel is " + cvChannelSupervisor.state() + capacityDetail();
            return new SubsystemStatus("cv-service", "CV inference", Health.OK, detail, null, null);
        }
        Instant since = Instant.now().minus(cvChannelSupervisor.outageFor());
        return new SubsystemStatus("cv-service", "CV inference", Health.DOWN, cvChannelSupervisor.describe(), since,
                "Check cv-service is running and reachable");
    }

    /**
     * @return {@code "; N of M streams, K sessions, J gate permits (<detector-client>)"} when {@link
     *         #inspect} is present and reachable, or {@code ""} otherwise — appended straight onto
     *         the OK sentence, so a deployment with no Inspect client wired reads byte-identically to
     *         before this field existed.
     */
    private String capacityDetail() {
        GrpcCvInspectClient client = inspect.get();
        if (client == null) {
            return "";
        }
        try {
            ProcessFacts facts = client.processFacts().getProcess();
            return "; " + facts.getStreamIdsCount() + " streams, " + facts.getSessions() + " sessions, "
                    + facts.getGatePermits() + " gate permits (" + facts.getDetectorClient() + ")";
        } catch (StatusRuntimeException e) {
            // Capacity is an enrichment, never a precondition: an unreachable/erroring Inspect call
            // must not turn an otherwise-healthy channel-reachability sentence into a failure.
            return "";
        }
    }
}
