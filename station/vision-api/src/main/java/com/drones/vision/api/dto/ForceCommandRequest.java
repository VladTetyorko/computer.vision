package com.drones.vision.api.dto;

/**
 * Request body for {@code POST /api/assets/{id}/arm} and {@code POST /api/assets/{id}/disarm}
 * (docs/plans/active/DRONE-INFRA-PLAN.md I-e Stage 2's frozen wire contract) — both carry the one optional
 * {@code force} flag, so one shared shape backs both endpoints.
 *
 * <p>The whole body is optional ({@code POST .../arm} with no body is valid); {@code force} defaults
 * to {@code false} when the body is absent or omits it.
 *
 * @param force whether to request a forced (pre-arm-check-bypassing) arm/disarm; nullable — a
 *              missing value means {@code false}
 */
public record ForceCommandRequest(Boolean force) {

    /** A missing-body constant, used when the request arrives with no body at all. */
    public static final ForceCommandRequest EMPTY = new ForceCommandRequest(null);

    /**
     * @return {@link #force()} defaulted to {@code false} when absent
     */
    public boolean forceOrDefault() {
        return force != null && force;
    }
}
