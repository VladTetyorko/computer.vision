package com.drones.vision.application.mark;

import com.drones.vision.domain.model.Affiliation;
import com.drones.vision.domain.model.AssetId;
import com.drones.vision.domain.model.LayerId;
import com.drones.vision.domain.model.MarkKind;
import com.drones.vision.application.map.LayerResolver;

/**
 * Everything needed to create a {@code DETECTION} {@link com.drones.vision.domain.model.Mark} from
 * an asset's current pose (the cockpit "geolocate" action) — docs/plans/done/MAP-REWORK-PLAN.md §3/§4.2,
 * superseding docs/plans/done/TACTICAL-MARKS-PLAN.md §2's shape.
 *
 * <p>{@link MarkService#geolocate} resolves {@code assetId}'s freshest telemetry itself (via {@code
 * UsageTracker#latestTelemetry}) and projects a ground point via {@code
 * com.drones.vision.domain.model.GeoProjection#project} using {@link #depressionDegrees()} — this
 * spec carries no position, unlike {@link MarkSpec}.
 *
 * @param assetId            the asset whose pose to project from
 * @param layerId            the layer this mark lands on, or {@code null} to use the creator's
 *                           default layer (see {@link LayerResolver#defaultLayerFor})
 * @param kind                the tactical category
 * @param affiliation        friend/enemy affiliation for symbology; must not be {@code null}
 * @param label               short human-readable label; must not be blank
 * @param note                optional free-text detail; blank normalizes to {@code null}
 * @param depressionDegrees  the assumed camera depression angle, degrees; defaulted at the wire
 *                            boundary to {@code GeoProjection.DEFAULT_DEPRESSION_DEGREES} when the
 *                            caller does not supply one — validated by {@code GeoProjection.project}
 *                            itself, not duplicated here
 */
public record GeolocateSpec(AssetId assetId, LayerId layerId, MarkKind kind, Affiliation affiliation, String label,
                             String note, double depressionDegrees) {

    public GeolocateSpec {
        if (assetId == null) {
            throw new IllegalArgumentException("GeolocateSpec assetId must not be null");
        }
        if (kind == null) {
            throw new IllegalArgumentException("GeolocateSpec kind must not be null");
        }
        if (affiliation == null) {
            throw new IllegalArgumentException("GeolocateSpec affiliation must not be null");
        }
        if (label == null || label.isBlank()) {
            throw new IllegalArgumentException("GeolocateSpec label must not be blank");
        }
        if (note != null && note.isBlank()) {
            note = null;
        }
    }
}
