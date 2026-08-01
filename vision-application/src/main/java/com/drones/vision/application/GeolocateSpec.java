package com.drones.vision.application;

import com.drones.vision.domain.model.AssetId;
import com.drones.vision.domain.model.MarkKind;

/**
 * Everything needed to create a {@code DETECTION} {@link com.drones.vision.domain.model.Mark} from
 * an asset's current pose (the cockpit "geolocate" action) — docs/TACTICAL-MARKS-PLAN.md §2.
 *
 * <p>{@link MarkService#geolocate} resolves {@code assetId}'s freshest telemetry itself (via {@code
 * UsageTracker#latestTelemetry}) and projects a ground point via {@code
 * com.drones.vision.domain.model.GeoProjection#project} using {@link #depressionDegrees()} — this
 * spec carries no position, unlike {@link MarkSpec}.
 *
 * @param assetId            the asset whose pose to project from
 * @param kind                the tactical category
 * @param label               short human-readable label; must not be blank
 * @param note                optional free-text detail; blank normalizes to {@code null}
 * @param depressionDegrees  the assumed camera depression angle, degrees; defaulted at the wire
 *                            boundary to {@code GeoProjection.DEFAULT_DEPRESSION_DEGREES} when the
 *                            caller does not supply one — validated by {@code GeoProjection.project}
 *                            itself, not duplicated here
 */
public record GeolocateSpec(AssetId assetId, MarkKind kind, String label, String note, double depressionDegrees) {

    public GeolocateSpec {
        if (assetId == null) {
            throw new IllegalArgumentException("GeolocateSpec assetId must not be null");
        }
        if (kind == null) {
            throw new IllegalArgumentException("GeolocateSpec kind must not be null");
        }
        if (label == null || label.isBlank()) {
            throw new IllegalArgumentException("GeolocateSpec label must not be blank");
        }
        if (note != null && note.isBlank()) {
            note = null;
        }
    }
}
