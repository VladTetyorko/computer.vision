package com.drones.vision.api.dto;

/**
 * Response body for {@code GET /api/auth/bootstrap} (docs/plans/active/AUTH-ROLES-PLAN.md §3.5, wave B3).
 *
 * @param required whether a first-admin bootstrap is still needed — {@code
 *                 vision.auth.enabled && no enabled user holds an ADMIN membership}, a one-way
 *                 latch: once {@code false}, it stays {@code false} for the life of the station
 */
public record BootstrapStatusResponse(boolean required) {
}
