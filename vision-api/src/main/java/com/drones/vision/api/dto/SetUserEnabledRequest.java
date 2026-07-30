package com.drones.vision.api.dto;

/**
 * Request body for {@code POST /api/users/{id}/enabled} (docs/U-SCOPE-PLAN.md, U-e slice 2) —
 * enable or disable a user's ability to authenticate.
 *
 * @param enabled the new enabled state
 */
public record SetUserEnabledRequest(boolean enabled) {
}
