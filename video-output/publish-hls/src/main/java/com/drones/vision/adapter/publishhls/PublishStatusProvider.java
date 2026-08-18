package com.drones.vision.adapter.publishhls;

import com.drones.vision.kernel.StreamId;
import com.drones.vision.platform.Health;
import com.drones.vision.platform.SubsystemStatus;
import com.drones.vision.platform.SubsystemStatusPort;
import java.util.List;
import java.util.stream.Collectors;

/**
 * {@code video-publish}'s {@link SubsystemStatusPort} (docs/plans/active/SYSTEM-STATUS-PLAN.md
 * §4.2): reports whether streams currently being pushed to mediamtx are actually getting through,
 * using {@link MediamtxStreamPublisher#streamsInOutage()} — the same {@link PublishBackoff} state
 * that already throttles reconnects and drops frames during a real outage.
 *
 * <p>Any stream in outage reports {@link Health#DEGRADED} — never {@link Health#DOWN} — per the
 * plan's mapping table: mediamtx being unreachable for some streams is recoverable/self-healing
 * (backoff keeps retrying) and other streams on this same publisher may still be fine, so "degraded"
 * is the honest word even when every currently-tracked stream happens to be affected.
 *
 * <p>Zero active streams is idle, not a fault — nothing is wrong with mediamtx just because nothing
 * is currently publishing to it — so that case reports {@link Health#OK}, unlike {@code
 * mavlink-link}'s "no vehicle claimed" (which reports {@link Health#UNKNOWN}): there the absence
 * itself may be the thing an operator wants to know about, here it is the ordinary steady state
 * between demos/flights.
 */
public final class PublishStatusProvider implements SubsystemStatusPort {

    private final MediamtxStreamPublisher publisher;

    public PublishStatusProvider(MediamtxStreamPublisher publisher) {
        this.publisher = publisher;
    }

    @Override
    public SubsystemStatus status() {
        MediamtxStreamPublisher.PublishSnapshot snapshot = publisher.streamsInOutage();
        if (snapshot.total() == 0) {
            return new SubsystemStatus("video-publish", "Video publish (mediamtx)", Health.OK,
                    "No streams are currently publishing", null, null);
        }
        List<StreamId> inOutage = snapshot.inOutage();
        if (inOutage.isEmpty()) {
            return new SubsystemStatus("video-publish", "Video publish (mediamtx)", Health.OK,
                    snapshot.total() + " stream(s) publishing to mediamtx normally", null, null);
        }
        String names = inOutage.stream().map(id -> id.value().toString()).collect(Collectors.joining(", "));
        String detail = inOutage.size() + "/" + snapshot.total() + " stream(s) in outage, backing off "
                + "reconnects to mediamtx: " + names;
        return new SubsystemStatus("video-publish", "Video publish (mediamtx)", Health.DEGRADED, detail, null,
                "Check mediamtx is running and reachable");
    }
}
