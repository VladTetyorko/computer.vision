package com.drones.vision.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Response body for {@code GET /api/streams/{streamId}/tracks} (docs/TRACKING-PLAN.md &sect;4.E's
 * frozen wire contract) — the in-memory track book of a running stream, plus the duty-cycle
 * counters over it.
 *
 * <p><b>Never errors.</b> An unknown or stopped stream is a {@code 200} with an empty {@code tracks}
 * list, {@code lockedTrackId: 0} and no {@code stats} — the same forgiving idiom {@code GET
 * /api/streams/{streamId}/detections} already uses, and the reason a polling client needs one code
 * path instead of two.
 *
 * <p>{@code @JsonInclude(NON_NULL)} covers exactly one field, {@code stats}: it is absent whenever
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
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record StreamTracksResponse(String streamId, long lockedTrackId, List<TrackResponse> tracks,
                                    TrackStatsResponse stats) {

    public StreamTracksResponse {
        tracks = List.copyOf(tracks);
    }
}
