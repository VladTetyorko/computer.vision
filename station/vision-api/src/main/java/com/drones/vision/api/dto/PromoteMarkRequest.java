package com.drones.vision.api.dto;

import com.drones.vision.map.domain.model.LayerId;

/**
 * Body of {@code POST /api/map/marks/{id}/promote} (docs/plans/done/MAP-REWORK-PLAN.md §4.1) — DELTA's
 * "verify, then share wider" step.
 *
 * <p>The whole body is optional, and so is its one field: an absent {@code targetLayerId} promotes
 * to the deployment's COP layer, which is the overwhelmingly common case ("share this with
 * everyone").
 *
 * @param targetLayerId where to move the mark, or absent for the COP layer
 */
public record PromoteMarkRequest(String targetLayerId) {

    /** The body a caller who sent none is treated as having sent — promote to the COP layer. */
    public static final PromoteMarkRequest EMPTY = new PromoteMarkRequest(null);

    /**
     * @return the target layer, or {@code null} for the COP layer
     * @throws IllegalArgumentException if present but not a canonical UUID (→ 400)
     */
    public LayerId toTarget() {
        return MapRequests.optionalLayerId(targetLayerId);
    }
}
