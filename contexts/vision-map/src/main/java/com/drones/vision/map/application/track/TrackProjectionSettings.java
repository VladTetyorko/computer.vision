package com.drones.vision.map.application.track;

import com.drones.vision.kernel.FixedCameraGeoSettings;

/**
 * Every caller-supplied number {@code TrackProjectionService} needs, bundled into one constructor
 * argument (java-clean-code SKILL.md §3 — "bundle collaborators rather than sprawl") rather than
 * three more scalar parameters on top of the ports/policy it already takes. Sourced entirely from
 * {@code vision.geo.fixed-camera.*} in the running app (§6); nothing here is a code constant
 * (CLAUDE.md rule 1).
 *
 * @param geoSettings          the D6 refusal thresholds, passed straight through to {@code
 *                             FixedCameraGeo.project}
 * @param trailMinDistanceMeters D7 decimation: a trail point is stored only after the track has
 *                             moved at least this far from the last stored point; must not be negative
 * @param trailMaxPointsPerTrack D7's per-track cap; must be positive
 */
public record TrackProjectionSettings(FixedCameraGeoSettings geoSettings, double trailMinDistanceMeters,
                                       int trailMaxPointsPerTrack) {

    public TrackProjectionSettings {
        if (geoSettings == null) {
            throw new IllegalArgumentException("TrackProjectionSettings geoSettings must not be null");
        }
        if (Double.isNaN(trailMinDistanceMeters) || Double.isInfinite(trailMinDistanceMeters) || trailMinDistanceMeters < 0.0) {
            throw new IllegalArgumentException(
                    "TrackProjectionSettings trailMinDistanceMeters must not be negative: " + trailMinDistanceMeters);
        }
        if (trailMaxPointsPerTrack <= 0) {
            throw new IllegalArgumentException(
                    "TrackProjectionSettings trailMaxPointsPerTrack must be positive: " + trailMaxPointsPerTrack);
        }
    }
}
