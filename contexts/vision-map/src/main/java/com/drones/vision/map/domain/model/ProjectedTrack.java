package com.drones.vision.map.domain.model;

import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.GeoPosition;

import java.time.Instant;

/**
 * The live, ephemeral read model for one tracked object a fixed camera has projected onto the
 * ground (docs/plans/active/FIXED-CAMERA-GEO-PLAN.md decision D3) — one object per
 * {@code (assetId, trackId)}, updated in place as new fixes arrive, never one row per point (that is
 * {@link TrackPoint}'s job, the durable trail). This is the payload {@link MapEvent}'s {@link
 * MapEvent.EntityType#TRACK} entity carries.
 *
 * <p>Unlike a {@link Mark}, a {@code ProjectedTrack} is never itself persisted — {@code
 * TrackProjectionService} holds it in memory and republishes it on every tick; only its trail
 * ({@link TrackPoint}) is written to storage. A track that drops out of the perception {@code
 * TrackBook}, or whose owning stream stops, gets a {@link MapEvent.Action#CLEARED} event and simply
 * stops being held — "nothing lingers, nothing pretends" (D3).
 *
 * @param assetId           the camera asset this track was seen by
 * @param trackId           the perception {@code TrackBook} id — stable for this track's lifetime
 * @param label             the tracked object's current label (e.g. "car")
 * @param layerId           the layer this track publishes to (the pose's {@code targetLayerId}, or
 *                          the COP layer if none was named)
 * @param position          the current projected ground point
 * @param rangeMeters       ground range from the camera to {@code position}; never negative
 * @param errorRadiusMeters the D6 uncertainty radius around {@code position}; never negative —
 *                          always drawn, never omitted (D6: "a 200 m-error estimate must never
 *                          render as a 5 m-accurate-looking dot")
 * @param updatedAt         when this fix was computed
 */
public record ProjectedTrack(AssetId assetId, long trackId, String label, LayerId layerId, GeoPosition position,
                              double rangeMeters, double errorRadiusMeters, Instant updatedAt) {

    public ProjectedTrack {
        if (assetId == null) {
            throw new IllegalArgumentException("ProjectedTrack assetId must not be null");
        }
        if (trackId < 0) {
            throw new IllegalArgumentException("ProjectedTrack trackId must not be negative: " + trackId);
        }
        if (label == null || label.isBlank()) {
            throw new IllegalArgumentException("ProjectedTrack label must not be blank");
        }
        if (layerId == null) {
            throw new IllegalArgumentException("ProjectedTrack layerId must not be null");
        }
        if (position == null) {
            throw new IllegalArgumentException("ProjectedTrack position must not be null");
        }
        if (Double.isNaN(rangeMeters) || Double.isInfinite(rangeMeters) || rangeMeters < 0.0) {
            throw new IllegalArgumentException("ProjectedTrack rangeMeters must not be negative: " + rangeMeters);
        }
        if (Double.isNaN(errorRadiusMeters) || Double.isInfinite(errorRadiusMeters) || errorRadiusMeters < 0.0) {
            throw new IllegalArgumentException("ProjectedTrack errorRadiusMeters must not be negative: " + errorRadiusMeters);
        }
        if (updatedAt == null) {
            throw new IllegalArgumentException("ProjectedTrack updatedAt must not be null");
        }
    }
}
