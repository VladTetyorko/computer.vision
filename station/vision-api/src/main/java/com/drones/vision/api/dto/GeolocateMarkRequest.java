package com.drones.vision.api.dto;

import com.drones.vision.api.support.EnumParsing;
import com.drones.vision.map.application.mark.GeolocateSpec;
import com.drones.vision.map.domain.model.Affiliation;
import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.GeoProjection;
import com.drones.vision.map.domain.model.MarkKind;

/**
 * Request body for {@code POST /api/map/marks/geolocate} (docs/plans/done/MAP-REWORK-PLAN.md §4.1) — the
 * cockpit "mark target" action: the mark's position is <em>projected</em> from the named asset's
 * freshest telemetry rather than supplied, so this body carries no coordinates at all.
 *
 * <p>Three fields default rather than fail, preserving the one-tap cockpit gesture the
 * docs/plans/done/TACTICAL-MARKS-PLAN.md version already had: an absent {@code kind}/{@code affiliation}/{@code
 * label} becomes {@link MarkKind#TARGET}/{@link Affiliation#HOSTILE}/{@value #DEFAULT_LABEL}.
 * {@code HOSTILE} is the honest default for a geolocated contact — it is the affiliation the old
 * {@code MarkKind.TARGET} carried implicitly, and the same one docs/plans/done/MAP-REWORK-PLAN.md §2.2's
 * migration table assigns to every pre-existing {@code TARGET} mark.
 *
 * <p><b>{@code depressionDegrees} does not default here (docs/plans/done/GEO-POSE-PLAN.md §4.3, wave V3).</b>
 * Before that wave it was substituted at this wire boundary with {@code
 * GeoProjection.DEFAULT_DEPRESSION_DEGREES} whenever absent, which made an explicit {@code 45.0} and
 * an omitted value indistinguishable by the time {@link GeolocateSpec} saw them. Now that the
 * projection can use a real gimbal depression reading, that would have been actively harmful: every
 * existing caller (the cockpit's "mark target" button sends no {@code depressionDegrees} at all)
 * would have looked like an explicit 45° override on every single call, permanently discarding real
 * measurements. So an absent/{@code null} value now stays {@code null} all the way to {@link
 * DefaultMarkService}, meaning "let the resolved pose decide"; only a value the caller actually sent
 * is treated as an override.
 *
 * @param assetId           which asset to project from; required
 * @param layerId           which layer to create it on, or absent for the caller's default layer
 * @param depressionDegrees an operator override for the camera depression below the horizon, or
 *                           absent/{@code null} to let the telemetry's own pose (gimbal reading if
 *                           present, else {@link GeoProjection#DEFAULT_DEPRESSION_DEGREES}) decide
 */
public record GeolocateMarkRequest(String assetId, String layerId, String kind, String affiliation, String label,
                                    String note, Double depressionDegrees) {

    /** The label a geolocated mark gets when the cockpit sends none. */
    static final String DEFAULT_LABEL = "Contact";

    /**
     * Converts this request to the application-layer spec.
     *
     * @return the equivalent {@link GeolocateSpec}
     * @throws IllegalArgumentException if {@code assetId} is blank/malformed, {@code layerId} is
     *                                   present but malformed, or {@code kind}/{@code affiliation}
     *                                   is present but unrecognized (→ 400)
     */
    public GeolocateSpec toSpec() {
        return new GeolocateSpec(
                requireAssetId(assetId),
                MapRequests.optionalLayerId(layerId),
                orDefault(EnumParsing.optional(MarkKind.class, "kind", kind), MarkKind.TARGET),
                orDefault(EnumParsing.optional(Affiliation.class, "affiliation", affiliation), Affiliation.HOSTILE),
                label == null || label.isBlank() ? DEFAULT_LABEL : label,
                note,
                depressionDegrees);
    }

    private static <T> T orDefault(T value, T fallback) {
        return value == null ? fallback : value;
    }

    private static AssetId requireAssetId(String assetId) {
        if (assetId == null || assetId.isBlank()) {
            throw new IllegalArgumentException("GeolocateMarkRequest assetId must not be blank");
        }
        return AssetId.of(assetId);
    }
}
