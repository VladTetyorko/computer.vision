package com.drones.vision.map.application.mark;

import com.drones.vision.map.domain.model.Affiliation;
import com.drones.vision.kernel.AssetId;
import com.drones.vision.map.domain.model.LayerId;
import com.drones.vision.map.domain.model.MarkKind;
import com.drones.vision.map.application.LayerResolver;

/**
 * Everything needed to create a {@code DETECTION} {@link com.drones.vision.map.domain.model.Mark} from
 * an asset's current pose (the cockpit "geolocate" action) — docs/plans/done/MAP-REWORK-PLAN.md §3/§4.2,
 * superseding docs/plans/done/TACTICAL-MARKS-PLAN.md §2's shape.
 *
 * <p>{@link MarkService#geolocate} resolves {@code assetId}'s freshest telemetry itself (via {@code
 * UsageTracker#latestTelemetry}) and projects a ground point via {@code
 * com.drones.vision.kernel.GeoProjection#aimFrom}/{@code GeoProjection#project(GeoPosition,
 * GeoProjection.CameraAim)} — this spec carries no position, unlike {@link MarkSpec}.
 *
 * @param assetId            the asset whose pose to project from
 * @param layerId            the layer this mark lands on, or {@code null} to use the creator's
 *                           default layer (see {@link LayerResolver#defaultLayerFor})
 * @param kind                the tactical category
 * @param affiliation        friend/enemy affiliation for symbology; must not be {@code null}
 * @param label               short human-readable label; must not be blank
 * @param note                optional free-text detail; blank normalizes to {@code null}
 * @param depressionDegrees  an operator-supplied override for the camera depression angle, degrees,
 *                            or {@code null} to let the resolved pose decide (docs/plans/done/GEO-POSE-PLAN.md
 *                            §4.3/V3): a real gimbal depression reading wins when the telemetry has
 *                            one, else {@code GeoProjection.DEFAULT_DEPRESSION_DEGREES}. A non-null
 *                            value here always wins over a gimbal reading — it means "override the
 *                            measurement", not "fall back if there is no measurement" — so it must
 *                            stay a genuine {@code null} rather than a pre-defaulted constant, or a
 *                            real gimbal fix would be silently discarded whenever a caller happens to
 *                            send the same value as the default. Range-validated by {@code
 *                            GeoProjection.CameraAim}'s own compact constructor when present, not
 *                            duplicated here
 */
public record GeolocateSpec(AssetId assetId, LayerId layerId, MarkKind kind, Affiliation affiliation, String label,
                             String note, Double depressionDegrees) {

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
