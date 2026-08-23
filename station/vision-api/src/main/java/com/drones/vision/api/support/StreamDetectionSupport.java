package com.drones.vision.api.support;

import com.drones.vision.api.live.LiveAndPollDetectionDemand;
import com.drones.vision.kernel.StreamId;
import com.drones.vision.perception.domain.model.PipelineConfig;

import java.util.Objects;

/**
 * Bundles {@code StreamController}'s two new detection-demand concerns
 * (docs/plans/done/CV-DEMAND-PLAN.md &sect;3.5/&sect;3.8) behind one constructor parameter,
 * deliberately: that controller already sits at four collaborators, and neither {@link
 * #defaultConfig()} (a single field read, once per {@code start}) nor {@link #touched(StreamId)} (a
 * single delegated call, once per {@code detections} read) is substantial enough on its own to
 * justify pushing the controller past this codebase's five-constructor-parameter ceiling
 * (.claude/skills/java-clean-code/SKILL.md &sect;3) — splitting the controller over two
 * single-method reads would be indirection for its own sake. This class exists to keep that
 * bundling honest and named, rather than an unlabeled extra field.
 *
 * <p>Plain class, constructed by {@code vision-app}'s wiring — not a {@code @Component} — mirroring
 * {@link SnapshotJpegEncoder}'s own precedent for a framework-free support class {@code vision-api}
 * holds but only {@code vision-app} can assemble (it alone knows whether {@code
 * vision.cv.demand.enabled} wired a real {@link LiveAndPollDetectionDemand} bean at all).
 *
 * @param defaultConfig the deployment's default {@link PipelineConfig} for a newly started
 *                       device-level stream (docs/plans/done/CV-DEMAND-PLAN.md &sect;3.8) — {@code
 *                       StartStreamRequest#mergeOnto} merges a request's overrides onto this instead
 *                       of the domain's static {@link PipelineConfig#defaults()}, so {@code
 *                       vision.cv.detection-default-enabled} actually reaches a started stream
 * @param demand        the demand port's concrete implementation, so {@link #touched(StreamId)} can
 *                       reach {@link LiveAndPollDetectionDemand#touched(StreamId)} directly; {@code
 *                       null} when {@code vision.cv.demand.enabled=false} (the port bean is absent
 *                       entirely), in which case {@link #touched(StreamId)} is a no-op
 */
public record StreamDetectionSupport(PipelineConfig defaultConfig, LiveAndPollDetectionDemand demand) {

    public StreamDetectionSupport {
        Objects.requireNonNull(defaultConfig, "defaultConfig must not be null");
        // demand is nullable -- see this record's own javadoc
    }

    /**
     * Stamps {@code streamId} as polled just now (docs/plans/done/CV-DEMAND-PLAN.md &sect;3.5) — a
     * no-op when {@link #demand()} is absent (the demand gate is not wired at all), the same
     * "nothing to do, quietly" posture every optional collaborator in this codebase takes.
     *
     * @param streamId the stream {@code GET /api/streams/{id}/detections} was just read for
     */
    public void touched(StreamId streamId) {
        if (demand != null) {
            demand.touched(streamId);
        }
    }
}
