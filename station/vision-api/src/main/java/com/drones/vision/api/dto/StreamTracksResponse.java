package com.drones.vision.api.dto;

import com.drones.vision.perception.domain.model.DetectionState;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Response body for {@code GET /api/streams/{streamId}/tracks} (docs/plans/done/TRACKING-PLAN.md &sect;4.E's
 * frozen wire contract) — the in-memory track book of a running stream, plus the duty-cycle
 * counters over it.
 *
 * <p><b>Never errors.</b> An unknown or stopped stream is a {@code 200} with an empty {@code tracks}
 * list, {@code lockedTrackId: 0} and no {@code stats} — the same forgiving idiom {@code GET
 * /api/streams/{streamId}/detections} already uses, and the reason a polling client needs one code
 * path instead of two.
 *
 * <p>{@code @JsonInclude(NON_NULL)} covers {@code stats}, {@code latency} and {@code rate}: {@code stats} is absent whenever
 * there is nothing honest to report (see {@code StreamController#tracks}), never a zeroed object —
 * the flow strip hides itself rather than showing a strip of zeros.
 *
 * @param streamId      the queried stream, canonical UUID string; echoed back even when nothing is running
 * @param lockedTrackId the track {@code FOLLOW} currently holds, {@code 0} when none — <b>top level,
 *                      not inside {@code stats}</b> (&sect;4.E), because it is a fact about the
 *                      stream rather than a statistic, and it is what the cockpit's
 *                      "Following #N — release" chip gates on
 * @param tracks        the booked tracks, ordered by {@code trackId} ascending
 * @param stats         the duty-cycle counters, or absent when the window has nothing to report yet
 * @param latency       what detections cost in wall-clock time, or absent before the first result.
 *                      Independent of {@code stats}: present whatever the tracking mode is
 *                      (docs/conclusions/CV-RATE-BUDGET.md &sect;3)
 * @param rate          why the stream is sampling at the rate it is, or absent before the first
 *                      sample. The companion to {@code latency} — that one is what a detection
 *                      cost, this one is how many were asked for and what became of them
 *                      (docs/plans/done/CV-RATE-CONTROL-PLAN.md &sect;1)
 * @param detectionState which of the two independent detection gates currently explains this
 *                       stream's boxes-or-no-boxes state (docs/plans/done/CV-DEMAND-PLAN.md
 *                       &sect;3.6), or absent for an unknown/not-running stream. See {@link
 *                       DetectionState}'s own javadoc: this reports gating, never health.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record StreamTracksResponse(String streamId, long lockedTrackId, List<TrackResponse> tracks,
                                    TrackStatsResponse stats, PipelineLatencyResponse latency,
                                    DetectionRateResponse rate, DetectionState detectionState) {

    public StreamTracksResponse {
        tracks = List.copyOf(tracks);
    }

    /**
     * The shape before {@code detectionState} was added, kept as a convenience constructor
     * defaulting it to absent — same "N-1-arg convenience ctor" idiom the domain records use, so
     * every pre-existing caller (and every test asserting the old body) compiles and behaves
     * unchanged.
     */
    public StreamTracksResponse(String streamId, long lockedTrackId, List<TrackResponse> tracks,
                                 TrackStatsResponse stats, PipelineLatencyResponse latency,
                                 DetectionRateResponse rate) {
        this(streamId, lockedTrackId, tracks, stats, latency, rate, null);
    }

    /**
     * The shape before {@code rate} was added, kept as a convenience constructor defaulting both
     * {@code rate} and {@code detectionState} to absent — same "N-1-arg convenience ctor" idiom, so
     * every pre-existing caller (and every test asserting the old body) compiles and behaves
     * unchanged.
     */
    public StreamTracksResponse(String streamId, long lockedTrackId, List<TrackResponse> tracks,
                                 TrackStatsResponse stats, PipelineLatencyResponse latency) {
        this(streamId, lockedTrackId, tracks, stats, latency, null, null);
    }
}
