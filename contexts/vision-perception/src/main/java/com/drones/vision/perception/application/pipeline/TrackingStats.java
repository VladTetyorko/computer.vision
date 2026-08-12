package com.drones.vision.perception.application.pipeline;

import com.drones.vision.perception.domain.model.DetectorReason;
import com.drones.vision.perception.domain.model.TrackState;
import com.drones.vision.perception.domain.model.TrackingMode;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * A snapshot of one stream's tracking flow over the last {@link #window} (docs/plans/done/TRACKING-PLAN.md
 * &sect;4.E, TRACKING-ORCHESTRATION.md &sect;5.4) — the read model behind {@code GET
 * /api/streams/{streamId}/tracks}'s {@code stats} object and the operator-facing flow strip.
 *
 * <p><b>Computed Java-side, from responses that already arrive.</b> Every field here is derived by
 * {@link TrackingStatsWindow} from the {@link com.drones.vision.perception.domain.model.TrackingTelemetry}
 * riding each {@link com.drones.vision.perception.domain.model.DetectionResult}. There is no new wire field, no
 * new endpoint, and no read-model concern inside cv-service (invariant P3) — which is also why this
 * works identically in every deployment placement, including onboard a companion computer where
 * nobody can read a log.
 *
 * @param mode               the tracking mode configured for the stream <i>at the moment of this
 *                           read</i> — a live config fact, not a window statistic, so it is passed
 *                           into {@link TrackingStatsWindow#snapshot(TrackingMode)} rather than
 *                           cached per sample (the mode is a hot knob and may have changed within
 *                           the window)
 * @param engineId           the engine that is actually <i>serving</i>, from the newest sample's
 *                           telemetry — not necessarily the one the stream's {@code TrackingConfig}
 *                           requested, since a degradation fallback may have swapped it
 *                           (docs/plans/done/TRACKING-PLAN.md &sect;5.I); empty when nothing has been sampled
 * @param window             the rolling window these counters cover; the API layer reports it as
 *                           {@code windowSeconds} ({@link Duration#toSeconds()})
 * @param detectorPasses     frames in the window that spent a detector pass ({@code detectorRan})
 * @param trackerFrames      frames in the window that did not ({@code !detectorRan}) — together
 *                           with {@code detectorPasses} this partitions the window exactly, which
 *                           is what makes {@code dutyRatio} a true fraction
 * @param dutyRatio          {@code detectorPasses / (detectorPasses + trackerFrames)} — the
 *                           fraction of sampled frames that cost a detector pass, {@code 0.0} for
 *                           an empty window. This is the number the whole duty cycle exists to
 *                           drive down, and the one an operator can read without {@code htop}
 * @param trackerMillisP50   median tracker latency in the window, milliseconds, nearest-rank over
 *                           <i>every</i> sample including detector-only frames (whose tracker
 *                           latency is {@code Duration.ZERO}); {@code 0.0} for an empty window
 * @param trackerMillisP95   95th-percentile tracker latency, same basis as {@code trackerMillisP50}
 * @param lastDetectorReason why the most recent detector pass in the window ran, or {@code null}
 *                           when no pass ran in it. Deliberately a window fact rather than a
 *                           per-frame one: "why is my detector still running?" is a question about
 *                           the recent past, and the per-frame answer already rides each SSE result
 * @param lockedTrackId      the track cv-service last confirmed as locked, {@code 0} = none. The
 *                           API layer surfaces this as the {@code tracks} response's own top-level
 *                           {@code lockedTrackId}, not inside its {@code stats} object. It is
 *                           reported from the wire, never from local intent, so a lock cv-service
 *                           could not honour reads as "not locked" instead of a lying chip
 *                           (TRACKING-ORCHESTRATION.md &sect;3.3)
 * @param byState            how many <b>distinct tracks</b> were observed in the window in each
 *                           {@link TrackState}, counting each track once by its newest state in the
 *                           window; always carries all four states, zero-filled, so the JSON shape
 *                           is stable. Distinct from {@link TrackBook#tracks()}, which lists the
 *                           tracks <i>currently</i> booked: a {@code LOST} track leaves the book at
 *                           once but still counts here until it falls out of the window
 */
public record TrackingStats(TrackingMode mode, String engineId, Duration window, long detectorPasses,
                             long trackerFrames, double dutyRatio, double trackerMillisP50,
                             double trackerMillisP95, DetectorReason lastDetectorReason, long lockedTrackId,
                             Map<TrackState, Integer> byState) {

    public TrackingStats {
        Objects.requireNonNull(mode, "mode must not be null");
        Objects.requireNonNull(engineId, "engineId must not be null");
        Objects.requireNonNull(window, "window must not be null");
        Objects.requireNonNull(byState, "byState must not be null");
        byState = Map.copyOf(byState);
    }

    /**
     * @param mode   the stream's currently configured tracking mode
     * @param window the window this stream's stats are collected over
     * @return the snapshot of a window in which nothing has been sampled yet — every counter zero,
     *         no engine, no reason, no lock, and all four {@link TrackState}s at zero. What a stream
     *         reports before its first tracked result, and immediately after a model re-arm clears
     *         the window.
     */
    public static TrackingStats empty(TrackingMode mode, Duration window) {
        return new TrackingStats(mode, "", window, 0L, 0L, 0.0, 0.0, 0.0, null, 0L, zeroedStates());
    }

    /** All four {@link TrackState}s mapped to {@code 0}, the zero-filled base every histogram starts from. */
    static Map<TrackState, Integer> zeroedStates() {
        Map<TrackState, Integer> states = new EnumMap<>(TrackState.class);
        for (TrackState state : TrackState.values()) {
            states.put(state, 0);
        }
        return states;
    }
}
