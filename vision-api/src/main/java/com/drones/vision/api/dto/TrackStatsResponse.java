package com.drones.vision.api.dto;

import com.drones.vision.perception.application.pipeline.TrackingStats;
import com.drones.vision.perception.domain.model.TrackState;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The {@code "stats"} object of {@code GET /api/streams/{streamId}/tracks} (docs/plans/done/TRACKING-PLAN.md
 * &sect;4.E) — the duty cycle made visible: how often the detector actually ran against how often
 * the tracker did, what that cost, and what the book currently holds.
 *
 * <p>Computed Java-side by {@code TrackingStatsWindow} (vision-application) from responses that
 * already arrive — no new wire field and no read-model concern inside cv-service (invariant P3,
 * docs/extracts/TRACKING-ORCHESTRATION.md &sect;5.4). It is what backs the Fly cockpit's flow strip, so the
 * plan's core claim is read off a screen instead of {@code htop} on a remote inference box.
 *
 * <p>No {@code @JsonInclude(NON_NULL)}, and that is load-bearing: <b>this object is either wholly
 * present with every field, or wholly absent</b> from the parent {@link StreamTracksResponse}. See
 * {@code StreamController#tracks} for when it is omitted — an object with a missing {@code
 * lastDetectorReason} would be a strip of half-facts, and the flow strip has no rendering for one.
 *
 * <p><b>{@code lockedTrackId} deliberately does not live here</b> — &sect;4.E hoists it to the
 * response's top level, because the held target is a fact about the stream, not a statistic over a
 * window, and it must stay readable when there are no stats at all.
 *
 * @param mode               the stream's currently configured tracking mode
 * @param engineId           the engine <b>actually serving</b> this stream, which is not necessarily
 *                           the one requested (docs/plans/done/TRACKING-PLAN.md R11); {@code ""} when none has
 *                           reported yet
 * @param windowSeconds      how far back the counters reach ({@code vision.tracking.stats-window-seconds})
 * @param detectorPasses     frames in the window that spent a full detector pass
 * @param trackerFrames      frames in the window served by the tracker alone
 * @param dutyRatio          {@code detectorPasses / (detectorPasses + trackerFrames)}
 * @param trackerMillisP50   median per-frame tracker cost over the window, milliseconds
 * @param trackerMillisP95   95th-percentile per-frame tracker cost over the window, milliseconds
 * @param lastDetectorReason why the newest detector pass in the window ran
 * @param byState            how many distinct track ids the window ended in each lifecycle state;
 *                           every state is a key, zero included, in declaration order
 */
public record TrackStatsResponse(String mode, String engineId, long windowSeconds, long detectorPasses,
                                  long trackerFrames, double dutyRatio, double trackerMillisP50,
                                  double trackerMillisP95, String lastDetectorReason, Map<String, Integer> byState) {

    public TrackStatsResponse {
        // A LinkedHashMap copy, not Map.copyOf: the iteration order IS the JSON key order, and the
        // states read best in lifecycle order rather than whatever an immutable map happens to pick.
        byState = Collections.unmodifiableMap(new LinkedHashMap<>(byState));
    }

    /**
     * Maps the application layer's window snapshot to its wire representation.
     *
     * @param stats the snapshot to map; its {@code lastDetectorReason} must be present — see this
     *              record's own javadoc and {@code StreamController#tracks}
     * @return the {@code "stats"} object
     */
    public static TrackStatsResponse from(TrackingStats stats) {
        Map<String, Integer> byState = new LinkedHashMap<>();
        for (TrackState state : TrackState.values()) {
            byState.put(state.name(), stats.byState().getOrDefault(state, 0));
        }
        return new TrackStatsResponse(stats.mode().name(), stats.engineId(), stats.window().toSeconds(),
                stats.detectorPasses(), stats.trackerFrames(), stats.dutyRatio(), stats.trackerMillisP50(),
                stats.trackerMillisP95(), stats.lastDetectorReason().name(), byState);
    }
}
