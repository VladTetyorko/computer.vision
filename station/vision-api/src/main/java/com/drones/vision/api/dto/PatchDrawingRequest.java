package com.drones.vision.api.dto;

import com.drones.vision.map.application.DrawingPatch;

import java.util.List;
import java.util.Optional;

/**
 * Request body for {@code PATCH /api/map/drawings/{id}} (docs/plans/done/MAP-REWORK-PLAN.md §4.2) — a true
 * partial patch: every field is optional and {@code null} means "leave unchanged".
 *
 * <p>{@code points}, when present, is a <strong>wholesale replacement</strong> of the geometry, not
 * a delta — the drag-a-vertex flow (Wave E) sends the full new vertex list. It must still satisfy
 * the drawing's own kind (≥ 2 points for {@code LINE}/{@code ARROW}, ≥ 3 for {@code POLYGON},
 * exactly 1 for {@code TEXT}), which {@code Drawing#withGeometry} enforces.
 *
 * @param points     the replacement geometry, or absent to leave it alone
 * @param label      the replacement label, or absent
 * @param colorToken the replacement design-system token name, or absent
 */
public record PatchDrawingRequest(List<PositionDto> points, String label, String colorToken) {

    /**
     * Converts this request to the application-layer patch.
     *
     * @return the equivalent {@link DrawingPatch}
     * @throws IllegalArgumentException if a supplied point is out of range (→ 400); point-count and
     *                                   token-format rules surface from the domain on apply
     */
    public DrawingPatch toPatch() {
        return new DrawingPatch(
                points == null ? Optional.empty() : Optional.of(CreateDrawingRequest.toPoints(points)),
                Optional.ofNullable(label),
                Optional.ofNullable(colorToken));
    }
}
