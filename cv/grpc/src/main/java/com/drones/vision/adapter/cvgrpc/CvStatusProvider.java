package com.drones.vision.adapter.cvgrpc;

import com.drones.vision.platform.Health;
import com.drones.vision.platform.SubsystemStatus;
import com.drones.vision.platform.SubsystemStatusPort;
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
 */
public final class CvStatusProvider implements SubsystemStatusPort {

    private final Supplier<CvChannelSupervisor> supervisor;

    public CvStatusProvider(Supplier<CvChannelSupervisor> supervisor) {
        this.supervisor = supervisor;
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
            return new SubsystemStatus("cv-service", "CV inference", Health.OK,
                    "cv-service channel is " + cvChannelSupervisor.state(), null, null);
        }
        Instant since = Instant.now().minus(cvChannelSupervisor.outageFor());
        return new SubsystemStatus("cv-service", "CV inference", Health.DOWN, cvChannelSupervisor.describe(), since,
                "Check cv-service is running and reachable");
    }
}
