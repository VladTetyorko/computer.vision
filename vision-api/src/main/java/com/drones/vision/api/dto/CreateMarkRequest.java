package com.drones.vision.api.dto;

import com.drones.vision.api.exception.ApiExceptionHandler;
import com.drones.vision.api.support.EnumParsing;
import com.drones.vision.application.mark.MarkSpec;
import com.drones.vision.domain.model.Affiliation;
import com.drones.vision.domain.model.MarkKind;

/**
 * Request body for {@code POST /api/map/marks} (docs/plans/done/MAP-REWORK-PLAN.md §4.2's frozen wire
 * contract) — a manual mark, dropped by a map click.
 *
 * <p>{@code kind}/{@code affiliation} are matched case-insensitively against their enum names via
 * {@link EnumParsing}, throwing {@link IllegalArgumentException} (→ 400 via {@link
 * ApiExceptionHandler}) for an unrecognized value and listing the valid ones. Position is
 * <em>flattened</em> into this record rather than nested, matching {@link MarkResponse}'s own shape
 * — a mark is a point, and the round trip reads the same on both sides.
 *
 * @param layerId        which layer to create it on, or absent to let {@code LayerResolver} pick the
 *                       caller's default (their first TEAM layer, else an auto-created PERSONAL one —
 *                       never the COP layer directly)
 * @param altitudeMeters metres, or absent if unknown
 * @param kind           {@code UNIT}/{@code EQUIPMENT}/{@code HAZARD}/{@code POI}/{@code TARGET}
 * @param affiliation    {@code FRIENDLY}/{@code HOSTILE}/{@code NEUTRAL}/{@code UNKNOWN}
 * @param label          short human-readable label; must not be blank
 * @param note           optional free-text detail; blank normalizes to {@code null}
 */
public record CreateMarkRequest(String layerId, double latitude, double longitude, Double altitudeMeters,
                                 String kind, String affiliation, String label, String note) {

    /**
     * Converts this request to the application-layer spec.
     *
     * @return the equivalent {@link MarkSpec}
     * @throws IllegalArgumentException if {@code layerId} is malformed, {@code kind}/{@code
     *                                   affiliation} is missing/unrecognized, the coordinates are out
     *                                   of range, or {@code label} is blank
     */
    public MarkSpec toSpec() {
        return new MarkSpec(
                MapRequests.optionalLayerId(layerId),
                EnumParsing.require(MarkKind.class, "kind", kind),
                EnumParsing.require(Affiliation.class, "affiliation", affiliation),
                label,
                note,
                new PositionDto(latitude, longitude, altitudeMeters).toPosition());
    }
}
