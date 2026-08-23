package com.drones.vision.map.domain.model;

import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.GeoPosition;

import java.time.Instant;

/**
 * One stored point of a {@link ProjectedTrack}'s durable trail (docs/plans/done/FIXED-CAMERA-GEO-PLAN.md
 * decision D3, §7 table {@code projected_track_points}) — append-only, decimated by {@code
 * TrackProjectionService} (a point is stored only after the track has moved
 * {@code trail.min-distance-meters} from the last stored point, D7), capped per track and pruned by
 * retention. Excluded from {@code db_audit_log} (§7) — high-volume, telemetry-character data, the
 * same classification V21 gave {@code detection_results}/{@code telemetry_samples}.
 *
 * <p>Unlike the live {@link ProjectedTrack}, a trail point carries no {@code rangeMeters} — only the
 * position and the error radius that applied when it was captured, which is all a rendered trail
 * needs.
 *
 * @param assetId          the camera asset this track belongs to
 * @param trackId          the perception {@code TrackBook} id this point belongs to
 * @param label            the tracked object's label at the time this point was captured (e.g. "car")
 * @param layerId          the layer this track publishes to, same as the live track's at capture time
 * @param position         the projected ground point
 * @param errorRadiusMeters the D6 uncertainty radius that applied to this fix; never negative
 * @param capturedAt       when this point was projected
 */
public record TrackPoint(AssetId assetId, long trackId, String label, LayerId layerId, GeoPosition position,
                          double errorRadiusMeters, Instant capturedAt) {

    public TrackPoint {
        if (assetId == null) {
            throw new IllegalArgumentException("TrackPoint assetId must not be null");
        }
        if (trackId < 0) {
            throw new IllegalArgumentException("TrackPoint trackId must not be negative: " + trackId);
        }
        if (label == null || label.isBlank()) {
            throw new IllegalArgumentException("TrackPoint label must not be blank");
        }
        if (layerId == null) {
            throw new IllegalArgumentException("TrackPoint layerId must not be null");
        }
        if (position == null) {
            throw new IllegalArgumentException("TrackPoint position must not be null");
        }
        if (Double.isNaN(errorRadiusMeters) || Double.isInfinite(errorRadiusMeters) || errorRadiusMeters < 0.0) {
            throw new IllegalArgumentException("TrackPoint errorRadiusMeters must not be negative: " + errorRadiusMeters);
        }
        if (capturedAt == null) {
            throw new IllegalArgumentException("TrackPoint capturedAt must not be null");
        }
    }
}
