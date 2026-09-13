package com.drones.vision.api.dto;

import com.drones.vision.perception.domain.model.DetectionState;
import com.drones.vision.perception.domain.model.FollowState;
import com.drones.vision.perception.domain.model.FollowStatus;
import com.drones.vision.perception.domain.model.TrackingStats;
import com.drones.vision.perception.domain.model.TracksSnapshot;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

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

    /**
     * Assembles this response from a {@link TracksSnapshot} (wave W9, CV-ORCHESTRATION-PLAN.md
     * §4.9, decision E25) — the gating rules below are carried verbatim from what used to be {@code
     * StreamController#tracks}'s own inline logic, moved here so the REST read and the {@code
     * tracks:} SSE topic push build from exactly one assembly ("one assembly, two transports").
     *
     * @param snapshot the snapshot to render; its {@code streamId} becomes this response's own
     * @param now      the instant to compute {@link FollowResponse#lastSeenAgeMillis()} against —
     *                 threaded through rather than read from {@link Instant#now()} here, so a caller
     *                 building many responses from one instant (or a test) controls it explicitly
     * @return the wire response for {@code snapshot}
     */
    public static StreamTracksResponse from(TracksSnapshot snapshot, Instant now) {
        List<TrackResponse> tracks = snapshot.tracks().stream()
                .filter(tracked -> tracked.detection().track() != null)
                .map(TrackResponse::from)
                .toList();
        TrackingStats stats = snapshot.stats().orElse(null);
        TrackStatsResponse statsResponse =
                stats == null || stats.lastDetectorReason() == null ? null : TrackStatsResponse.from(stats);
        // Gated on having sampled anything at all, NOT on `stats`: a stream with tracking off
        // reports latency and no stats, which is the combination this endpoint most needs to serve.
        PipelineLatencyResponse latencyResponse = snapshot.latency()
                .filter(latency -> latency.samples() > 0L)
                .map(PipelineLatencyResponse::from)
                .orElse(null);
        // Gated on a served deadline rather than on a completed one, so a stream whose samples are
        // ALL being dropped -- the case this object exists to diagnose -- still reports why.
        DetectionRateResponse rateResponse = snapshot.rate()
                .filter(rate -> rate.due() > 0L)
                .map(DetectionRateResponse::from)
                .orElse(null);
        DetectionState detectionState = snapshot.detectionState().orElse(null);
        Optional<FollowStatus> follow = snapshot.follow();
        // D4's bug fix: the confirmed-from-the-wire held target, sourced from the lock's own
        // lifecycle rather than the decaying stats window. `follow.trackId()` itself stays at its
        // last-bound value through LOST (so a re-acquire affordance can still name the target), so
        // this top-level field is gated on state rather than reading FollowStatus::trackId directly.
        long lockedTrackId = follow
                .filter(status -> status.state() == FollowState.HOLDING || status.state() == FollowState.COASTING)
                .map(FollowStatus::trackId)
                .orElse(0L);
        FollowResponse followResponse = follow.map(status -> FollowResponse.from(status, now)).orElse(null);
        List<WorldObjectResponse> objects =
                snapshot.objects().stream().map(WorldObjectResponse::from).toList();
        return new StreamTracksResponse(snapshot.streamId().value().toString(), lockedTrackId, tracks, statsResponse,
                latencyResponse, rateResponse, detectionState, followResponse, objects);
    }
}
