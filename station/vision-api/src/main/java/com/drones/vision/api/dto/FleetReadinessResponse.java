package com.drones.vision.api.dto;

import java.util.List;

/**
 * Response body for {@code GET /api/fleet/readiness} (docs/plans/active/DRONE-ONBOARDING-PLAN.md
 * §8.1, frozen): {@code { "assets": [ReadinessRow] } }.
 *
 * @param assets one {@link ReadinessRowResponse} per asset the caller's scope includes
 */
public record FleetReadinessResponse(List<ReadinessRowResponse> assets) {
}
