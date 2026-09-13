package com.drones.vision.perception.domain.model;

import com.drones.vision.kernel.StreamId;

import java.util.List;
import java.util.Optional;

/**
 * One stream's complete tracks-topic snapshot (docs/plans/active/CV-ORCHESTRATION-PLAN.md §4.9,
 * wave W9, decision E25) — every read model {@code GET /api/streams/{id}/tracks} has ever
 * assembled, bundled behind a single call so the same assembly can back both that REST read and
 * the {@code tracks:} SSE topic push, instead of each transport re-deriving it from five separate
 * {@code StreamService} calls at a different instant.
 *
 * <p>Built in exactly one place, {@code StreamPipeline#tracksSnapshot()}, at the same point in
 * {@code onDetectionResult} that used to compute only {@code foldedWorldObjects} — so a snapshot
 * this record carries and a snapshot the pipeline just folded are always the same instant's data,
 * never two reads racing each other. {@code station/vision-api}'s {@code StreamTracksResponse}
 * carries the gating rules (which fields the wire hides when empty, {@code lockedTrackId}'s
 * state rule) — this record carries no gating of its own, only the raw values.
 *
 * <p>Wave W9.0a (decision E27) moved {@link TrackingStats}, {@link PipelineLatency} and
 * {@link DetectionRate} into this package specifically so this record — and the port it travels
 * over, {@link com.drones.vision.perception.domain.port.DetectionLiveUpdatePort} — could live in
 * {@code domain.model} like every other read model here; the window classes that compute them
 * ({@code TrackingStatsWindow}, {@code PipelineLatencyWindow}, {@code DetectionRateWindow}) still
 * live in {@code application.pipeline}.
 *
 * @param streamId       the stream this snapshot describes; must not be {@code null}
 * @param tracks         every track currently booked, ordered by {@code trackId} ascending; same
 *                        as {@code StreamPipeline#tracks()}; defensively copied, never {@code
 *                        null}, may be empty
 * @param stats          this stream's tracking-flow counters over the stats window, or {@link
 *                        Optional#empty()} exactly when the stream is unknown/not running; present
 *                        but zeroed for a running stream on which nothing has been sampled yet
 *                        (see {@link TrackingStats#empty})
 * @param latency        this stream's wall-clock detection latency over the stats window, same
 *                        emptiness rule as {@code stats}
 * @param rate           this stream's sampler accounting over the stats window, same emptiness
 *                        rule as {@code stats}
 * @param detectionState which detection gate currently explains this stream's boxes-or-no-boxes
 *                        state, same emptiness rule as {@code stats}
 * @param follow         the current state of whichever {@code FOLLOW} lock this stream's operator
 *                        holds, or {@link Optional#empty()} if no lock has ever been issued, the
 *                        most recent lock action was a release, or the stream is unknown/not
 *                        running
 * @param objects        every object this stream's world fold currently owns — the same
 *                        identities {@code tracks} lists plus the operator/event/render relations
 *                        this platform owns on top of them (see {@link WorldObject}); defensively
 *                        copied, never {@code null}, may be empty
 */
public record TracksSnapshot(StreamId streamId, List<TrackedObject> tracks, Optional<TrackingStats> stats,
                              Optional<PipelineLatency> latency, Optional<DetectionRate> rate,
                              Optional<DetectionState> detectionState, Optional<FollowStatus> follow,
                              List<WorldObject> objects) {

    public TracksSnapshot {
        if (streamId == null) {
            throw new IllegalArgumentException("TracksSnapshot streamId must not be null");
        }
        if (tracks == null) {
            throw new IllegalArgumentException("TracksSnapshot tracks must not be null");
        }
        if (stats == null) {
            throw new IllegalArgumentException("TracksSnapshot stats must not be null");
        }
        if (latency == null) {
            throw new IllegalArgumentException("TracksSnapshot latency must not be null");
        }
        if (rate == null) {
            throw new IllegalArgumentException("TracksSnapshot rate must not be null");
        }
        if (detectionState == null) {
            throw new IllegalArgumentException("TracksSnapshot detectionState must not be null");
        }
        if (follow == null) {
            throw new IllegalArgumentException("TracksSnapshot follow must not be null");
        }
        if (objects == null) {
            throw new IllegalArgumentException("TracksSnapshot objects must not be null");
        }
        tracks = List.copyOf(tracks);
        objects = List.copyOf(objects);
    }

    /**
     * @param streamId the stream that has no running pipeline
     * @return the honest "nothing to report" snapshot for a stream that is unknown or not running
     *         on this instance — every read model empty, the same forgiving idiom {@code
     *         StreamService#tracks}/{@code #worldObjects} already use for their own return values.
     */
    public static TracksSnapshot empty(StreamId streamId) {
        return new TracksSnapshot(streamId, List.of(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), List.of());
    }
}
