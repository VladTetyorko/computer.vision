package com.drones.vision.map.application.track;

import com.drones.vision.map.domain.model.CameraPose;
import com.drones.vision.perception.domain.model.TrackedObject;

import java.time.Instant;
import java.util.List;

/**
 * One tick's worth of work for {@link TrackProjectionService#project} — a calibrated asset's pose
 * plus perception's own current track list for its stream (docs/plans/done/FIXED-CAMERA-GEO-PLAN.md
 * decision D2: "hands {@code (pose, List<TrackedObject>)} to map's {@code TrackProjectionService}" —
 * the composition {@code vision-app}'s {@code TrackProjectionRunner} performs each tick, resolving
 * the calibrated asset's active stream and calling perception's {@code StreamService#tracks}).
 * Naming {@link TrackedObject} here is a new reference inside the already-legal {@code map →
 * perception} edge, not a new context edge (D2, the same rule docs/plans/active/
 * DRONE-ONBOARDING-PLAN.md §7 used).
 *
 * @param pose              the projecting asset's stored camera pose ({@code pose.assetId()}
 *                          identifies which asset this tick is for — no separate field, so the two
 *                          can never disagree)
 * @param imageWidthPixels  the stream's current frame width, pixels; must be positive
 * @param imageHeightPixels the stream's current frame height, pixels; must be positive
 * @param tracks            perception's current track list for this asset's stream, in whatever
 *                          order {@code StreamService#tracks} returned it; defensively copied,
 *                          {@code null} normalizes to empty (a stream with nothing tracked right now)
 * @param observedAt        when this tick was taken — stamped on every {@code ProjectedTrack}/{@code
 *                          TrackPoint} this call produces; caller-supplied so the service never calls
 *                          {@code Instant.now()} itself and stays deterministic under test
 */
public record TrackProjectionInput(CameraPose pose, int imageWidthPixels, int imageHeightPixels,
                                    List<TrackedObject> tracks, Instant observedAt) {

    public TrackProjectionInput {
        if (pose == null) {
            throw new IllegalArgumentException("TrackProjectionInput pose must not be null");
        }
        if (imageWidthPixels <= 0) {
            throw new IllegalArgumentException("TrackProjectionInput imageWidthPixels must be positive: " + imageWidthPixels);
        }
        if (imageHeightPixels <= 0) {
            throw new IllegalArgumentException("TrackProjectionInput imageHeightPixels must be positive: " + imageHeightPixels);
        }
        tracks = tracks == null ? List.of() : List.copyOf(tracks);
        if (observedAt == null) {
            throw new IllegalArgumentException("TrackProjectionInput observedAt must not be null");
        }
    }
}
