package com.drones.vision.api.dto;

import com.drones.vision.domain.model.Detection;
import com.drones.vision.domain.model.TrackRef;
import com.drones.vision.domain.model.TrackedObject;

import java.time.Instant;

/**
 * One entry of {@code GET /api/streams/{streamId}/tracks}'s {@code "tracks"} array
 * (docs/plans/done/TRACKING-PLAN.md &sect;4.E's frozen wire contract) — the application layer's track-book
 * entry ({@code TrackedObject}, vision-domain) flattened for the wire.
 *
 * <p>Flat here, unlike {@link DetectionResponse}'s nested {@code "track"} object, and deliberately
 * so: this <i>is</i> the track resource, so there is no parent for the track facts to be nested
 * inside — grouping exists to keep an unrelated payload byte-identical when tracking is off
 * (docs/extracts/TRACKING-ORCHESTRATION.md &sect;6 rule 1), which does not apply to a response that is
 * nothing but tracks.
 *
 * <p>No {@code @JsonInclude(NON_NULL)} — every field is always present. A book entry always carries
 * a {@link TrackRef} ({@code TrackBook} ignores untracked detections outright), so {@code
 * state}/{@code source}/the velocities are never absent.
 *
 * @param trackId    the stable track id, the book's own key
 * @param label      the latest observation's class label
 * @param confidence the latest observation's confidence, [0,1]
 * @param box        the latest observation's bounding box, normalized [0,1]
 * @param state      {@code TENTATIVE}/{@code CONFIRMED}/{@code COASTING}/{@code LOST}
 * @param source     {@code DETECTOR}/{@code TRACKER} — which loop produced the latest observation
 * @param velocityX  normalized frame-widths per second
 * @param velocityY  normalized frame-heights per second
 * @param ageFrames  frames since this track was born
 * @param firstSeen  when this id was first booked
 * @param lastSeen   the latest observation's capture instant
 */
public record TrackResponse(long trackId, String label, double confidence, BoundingBoxResponse box, String state,
                             String source, double velocityX, double velocityY, int ageFrames, Instant firstSeen,
                             Instant lastSeen) {

    /**
     * Maps a booked {@link TrackedObject} to its wire representation.
     *
     * @param tracked the book entry to map; its detection must carry a {@link TrackRef}
     * @return the response element for {@code tracked}
     */
    public static TrackResponse from(TrackedObject tracked) {
        Detection detection = tracked.detection();
        TrackRef track = detection.track();
        return new TrackResponse(tracked.trackId(), detection.label(), detection.confidence(),
                BoundingBoxResponse.from(detection.box()), track.state().name(), track.source().name(),
                track.velocityX(), track.velocityY(), track.ageFrames(), tracked.firstSeen(), tracked.lastSeen());
    }
}
