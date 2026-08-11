package com.drones.vision.api.dto;

import com.drones.vision.perception.domain.model.TrackRef;

/**
 * The nested {@code "track"} object on {@link DetectionResponse} (docs/plans/done/TRACKING-PLAN.md &sect;4.G) —
 * one detection's track identity for this frame.
 *
 * <p><b>Nested, not five flat fields on the parent</b> (docs/extracts/TRACKING-ORCHESTRATION.md &sect;6 rule
 * 1): an untracked detection omits one key instead of five, so its payload stays byte-identical to
 * the pre-tracking wire, and a client gets a single null check ({@code d.track?.id}) gating all
 * track rendering rather than five optional fields that can disagree.
 *
 * <p>{@code ageFrames} is deliberately <b>not</b> here — it is book-keeping {@code GET
 * /api/streams/{streamId}/tracks} carries ({@link TrackResponse}), not something a box needs six
 * times a second (&sect;4.G).
 *
 * @param id         the stable track id; always &ge;1 ({@code 0} is the wire's untracked sentinel and
 *                   never reaches the domain, so it can never reach this DTO either)
 * @param state      {@code TENTATIVE}/{@code CONFIRMED}/{@code COASTING}/{@code LOST}, the enum name verbatim
 * @param source     {@code DETECTOR}/{@code TRACKER} — which of the two loops produced this box on this frame
 * @param velocityX  normalized frame-widths per second
 * @param velocityY  normalized frame-heights per second
 */
public record DetectionTrackResponse(long id, String state, String source, double velocityX, double velocityY) {

    /**
     * Maps a domain {@link TrackRef} to its wire representation.
     *
     * @param track the per-detection track facts to map; never {@code null}
     * @return the nested {@code "track"} object
     */
    public static DetectionTrackResponse from(TrackRef track) {
        return new DetectionTrackResponse(track.trackId(), track.state().name(), track.source().name(),
                track.velocityX(), track.velocityY());
    }
}
