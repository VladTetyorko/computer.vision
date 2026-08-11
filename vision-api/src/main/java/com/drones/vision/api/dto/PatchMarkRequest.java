package com.drones.vision.api.dto;

import com.drones.vision.api.support.EnumParsing;
import com.drones.vision.application.mark.MarkPatch;
import com.drones.vision.map.domain.model.Affiliation;
import com.drones.vision.kernel.GeoPosition;
import com.drones.vision.map.domain.model.MarkKind;
import com.drones.vision.map.domain.model.MarkStatus;

import java.util.Optional;

/**
 * Request body for {@code PATCH /api/map/marks/{id}} (docs/plans/done/MAP-REWORK-PLAN.md §4.2) — a true
 * partial patch: every field is optional and {@code null} means "leave unchanged".
 *
 * <p><strong>Position is all-or-nothing.</strong> {@code latitude} and {@code longitude} must be
 * supplied together (half a move is meaningless) — supplying one without the other is a {@code 400}
 * rather than a silently-ignored field. {@code altitudeMeters} rides along with them and may be
 * {@code null} to clear the altitude, which is exactly why it cannot be patched on its own.
 *
 * @param kind        {@code UNIT}/{@code EQUIPMENT}/{@code HAZARD}/{@code POI}/{@code TARGET}
 * @param affiliation {@code FRIENDLY}/{@code HOSTILE}/{@code NEUTRAL}/{@code UNKNOWN}
 * @param status      {@code ACTIVE} or {@code CLEARED} — the lifecycle transition; verification is
 *                    its own endpoint ({@code POST .../verify}), never a patch field
 */
public record PatchMarkRequest(Double latitude, Double longitude, Double altitudeMeters, String kind,
                                String affiliation, String label, String note, String status) {

    /**
     * Converts this request to the application-layer patch.
     *
     * @return the equivalent {@link MarkPatch} ({@link MarkPatch#NOTHING}-equivalent for an
     *         all-absent body)
     * @throws IllegalArgumentException if only one of {@code latitude}/{@code longitude} is present,
     *                                   the coordinates are out of range, or {@code kind}/{@code
     *                                   affiliation}/{@code status} is present but unrecognized (→ 400)
     */
    public MarkPatch toPatch() {
        return new MarkPatch(
                Optional.ofNullable(EnumParsing.optional(MarkKind.class, "kind", kind)),
                Optional.ofNullable(EnumParsing.optional(Affiliation.class, "affiliation", affiliation)),
                Optional.ofNullable(label),
                Optional.ofNullable(note),
                Optional.ofNullable(position()),
                Optional.ofNullable(EnumParsing.optional(MarkStatus.class, "status", status)));
    }

    private GeoPosition position() {
        if (latitude == null && longitude == null) {
            return null;
        }
        if (latitude == null || longitude == null) {
            throw new IllegalArgumentException(
                    "PatchMarkRequest latitude and longitude must be supplied together to move a mark");
        }
        return new GeoPosition(latitude, longitude, altitudeMeters);
    }
}
