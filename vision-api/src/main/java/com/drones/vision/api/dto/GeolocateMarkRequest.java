package com.drones.vision.api.dto;

import com.drones.vision.application.mark.GeolocateSpec;
import com.drones.vision.domain.model.AssetId;
import com.drones.vision.domain.model.GeoProjection;
import com.drones.vision.domain.model.MarkKind;

/**
 * Request body for {@code POST /api/marks/geolocate} (docs/TACTICAL-MARKS-PLAN.md §4's frozen wire
 * contract) — the cockpit "geolocate" action: the server reads {@code assetId}'s freshest telemetry
 * and projects a ground point ahead of the drone.
 *
 * <p>Two fields default rather than fail when absent, so the cockpit's one-tap "Mark target" action
 * (docs/TACTICAL-MARKS-PLAN.md's M5 UX) needs to send only {@code assetId}:
 * <ul>
 *   <li>{@code kind} absent/blank defaults to {@link MarkKind#TARGET} — the natural reading of a
 *       cockpit geolocate with no explicit category, matching the "Mark target" action name. An
 *       explicit but unrecognized value still 400s, same idiom as {@link CreateMarkRequest}.</li>
 *   <li>{@code label} absent/blank defaults to {@value #DEFAULT_LABEL} — {@link GeolocateSpec}
 *       itself requires a non-blank label, so this request supplies one rather than surfacing that
 *       as a caller-facing validation failure.</li>
 *   <li>{@code depressionDegrees} absent defaults to {@link GeoProjection#DEFAULT_DEPRESSION_DEGREES}
 *       — the platform's documented assumed camera angle (docs/TACTICAL-MARKS-PLAN.md Open Q1:
 *       fixed, not operator-exposed in v1).</li>
 * </ul>
 *
 * @param assetId            the asset to project from, as a canonical UUID string; required
 * @param kind               {@code "TARGET"}, {@code "HAZARD"}, {@code "POI"}, or {@code
 *                           "FRIENDLY"}, matched case-insensitively; absent/blank defaults to
 *                           {@code "TARGET"}
 * @param label              short human-readable label; absent/blank defaults to {@value
 *                           #DEFAULT_LABEL}
 * @param note               optional free-text detail; blank normalizes to {@code null}
 * @param depressionDegrees  the assumed camera depression angle, degrees; absent defaults to
 *                           {@link GeoProjection#DEFAULT_DEPRESSION_DEGREES}
 */
public record GeolocateMarkRequest(String assetId, String kind, String label, String note,
                                    Double depressionDegrees) {

    static final String DEFAULT_LABEL = "Contact";

    /**
     * Converts this request to the application-layer spec.
     *
     * @return the equivalent {@link GeolocateSpec}
     * @throws IllegalArgumentException if {@code assetId} is missing/blank/malformed, or {@code
     *                                   kind} is present but unrecognized
     */
    public GeolocateSpec toSpec() {
        return new GeolocateSpec(requireAssetId(assetId), effectiveKind(kind), effectiveLabel(label), note,
                depressionDegrees == null ? GeoProjection.DEFAULT_DEPRESSION_DEGREES : depressionDegrees);
    }

    private static AssetId requireAssetId(String assetId) {
        if (assetId == null || assetId.isBlank()) {
            throw new IllegalArgumentException("GeolocateMarkRequest assetId must not be blank");
        }
        return AssetId.of(assetId);
    }

    private static MarkKind effectiveKind(String kind) {
        return kind == null || kind.isBlank() ? MarkKind.TARGET : CreateMarkRequest.toKind(kind);
    }

    private static String effectiveLabel(String label) {
        return label == null || label.isBlank() ? DEFAULT_LABEL : label;
    }
}
