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
 * <p>{@code @JsonInclude(NON_NULL)} covers {@code stats}, {@code latency}, {@code rate} and {@code
 * follow}: {@code stats} is absent whenever there is nothing honest to report (see {@code
 * StreamController#tracks}), never a zeroed object — the flow strip hides itself rather than
 * showing a strip of zeros. {@code follow} follows the same rule (docs/plans/active/TRACK-FOLLOW-PLAN.md
 * &sect;3.1 decision 3): absent, not a zeroed/empty object, until an operator has actually issued a
 * lock.
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
 * @param follow         the {@code FOLLOW} lock's own lifecycle (docs/plans/active/TRACK-FOLLOW-PLAN.md
 *                       &sect;3.1), or absent when no lock has ever been issued on this stream, or
 *                       the most recent lock action was a release — the same "never a zeroed
 *                       object" idiom {@code stats} already uses. {@code lockedTrackId} above is a
 *                       fact derived from this same source (see {@code StreamController#tracks}),
 *                       not from {@code stats} any more (D4): the two must never be read as
 *                       independent
 * @param objects        this stream's current world-object fold (docs/plans/active/CV-ORCHESTRATION-PLAN.md
 *                       §4.5/§4.6, waves W1/W2.8) — sourced from {@code StreamService#worldObjects},
 *                       the same "never null, empty for an unknown/stopped stream" idiom {@code
 *                       tracks} already uses; unlike {@code stats}/{@code latency}/{@code rate}/
 *                       {@code follow}, always present as a JSON array, never omitted. Carries the
 *                       operator/event/render relations {@code DetectionResultResponse#objects}
 *                       deliberately does not — see {@link WorldObjectResponse}'s own javadoc
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record StreamTracksResponse(String streamId, long lockedTrackId, List<TrackResponse> tracks,
                                    TrackStatsResponse stats, PipelineLatencyResponse latency,
                                    DetectionRateResponse rate, DetectionState detectionState,
                                    FollowResponse follow, List<WorldObjectResponse> objects) {

    public StreamTracksResponse {
        tracks = List.copyOf(tracks);
        objects = List.copyOf(objects);
    }
}
