package com.drones.vision.api.dto;

import com.drones.vision.perception.domain.model.PipelineConfig;
import com.drones.vision.perception.domain.model.TrackingConfig;

import java.util.List;

/**
 * Response body for {@code GET /api/streams/{streamId}/config}
 * (docs/plans/active/STREAM-STATE-PLAN.md &sect;2.5) — <b>the read half of a knob that was write-only
 * over HTTP.</b>
 *
 * <p>Before this endpoint, {@code PATCH .../config} could change detection on/off, the model, the
 * label filter and the whole tracking configuration, and <i>nothing could read any of it back</i>.
 * A client rendering those controls had no choice but to display its own local memory of what it
 * believed it had sent — which stops being true the moment the stream restarts, a second client
 * patches it, or the browser's stored copy outlives the stream it described. That is exactly how the
 * cockpit's Detect switch came to render a {@code localStorage} draft instead of the stream.
 *
 * <p><b>Field names deliberately mirror {@link UpdateStreamConfigRequest} one-for-one</b>, so a
 * client reads and writes the same vocabulary and no mapping table has to be kept in sync by hand.
 * The one shape difference is intentional: every field here is <i>always present</i>, because this
 * is the effective configuration rather than a patch, and {@code null} on the request side means
 * "leave unchanged" — a meaning that would be nonsense in a response.
 *
 * @param model               the running checkpoint id (never the version — the same identifier
 *                            {@code UpdateStreamConfigRequest#model} accepts)
 * @param confidenceThreshold the running confidence threshold, in [0,1]
 * @param inferenceFps        the requested inference rate. <b>What the detector actually achieves
 *                            can be far lower</b>; {@code GET .../tracks}'s {@code rate} object is
 *                            the measured truth, and this field must never be rendered as one
 * @param labelFilter         the running class filter; <b>empty means "every class"</b>, matching
 *                            {@code PipelineConfig}'s own convention, not "no classes"
 * @param labelDenyFilter     the running class deny list (docs/plans/active/CV-CLEAN-FEED-PLAN.md D-2);
 *                            <b>empty means "deny nothing"</b>. Applied alongside {@code labelFilter}
 *                            at the single drop site in {@code StreamPipeline}, never a separate stage
 * @param detectionEnabled    the operator's own per-stream detect-on/off intent — one of the two
 *                            independent gates (docs/plans/active/CV-DEMAND-PLAN.md &sect;1). This
 *                            says what was <i>asked for</i>, never whether inference is running;
 *                            {@code GET .../tracks}'s {@code detectionState} answers that
 * @param tracking            the running tracking configuration
 */
public record StreamConfigResponse(String model, double confidenceThreshold, int inferenceFps,
                                    List<String> labelFilter, List<String> labelDenyFilter, boolean detectionEnabled,
                                    StreamTrackingConfigResponse tracking) {

    /**
     * Maps the running configuration to the wire, field for field.
     *
     * @param config the pipeline's effective configuration
     * @return the response body describing it
     */
    public static StreamConfigResponse from(PipelineConfig config) {
        return new StreamConfigResponse(config.model().id(), config.confidenceThreshold(), config.inferenceFps(),
                List.copyOf(config.labelFilter()), List.copyOf(config.labelDenyFilter()), config.detectionEnabled(),
                StreamTrackingConfigResponse.from(config.tracking()));
    }

    /**
     * The tracking half, mirroring {@link TrackingConfigRequest}'s own field names.
     *
     * <p>{@code lock} is deliberately <b>not</b> echoed here even though {@code TrackingConfig}
     * carries one. A held target is confirmed from {@code GET .../tracks}'s own {@code lockedTrackId}
     * and nowhere else — docs/extracts/TRACKING-ORCHESTRATION.md &sect;3.3's honesty rule, which the
     * cockpit's "Following #N" chip already obeys. Publishing the requested lock on a second surface
     * would give a client a way to render a lock that was asked for but never acquired.
     *
     * @param capabilityLevel the requested ceiling, {@code 0} meaning auto-probe — <b>not</b> what
     *                        cv-service actually served, which rides {@code GET .../tracks}
     */
    public record StreamTrackingConfigResponse(String mode, String engineId, int verifyEveryMillis, int followFps,
                                                int redetectIouPercent, int maxAgeFrames, int minHits,
                                                int capabilityLevel, int reupdateMaxGapMillis) {

        static StreamTrackingConfigResponse from(TrackingConfig tracking) {
            return new StreamTrackingConfigResponse(tracking.mode().name(), tracking.engineId(),
                    tracking.verifyEveryMillis(), tracking.followFps(), tracking.redetectIouPercent(),
                    tracking.maxAgeFrames(), tracking.minHits(), tracking.capabilityLevel(),
                    tracking.reupdateMaxGapMillis());
        }
    }
}
