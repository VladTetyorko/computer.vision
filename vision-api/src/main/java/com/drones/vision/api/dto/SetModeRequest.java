package com.drones.vision.api.dto;

/**
 * Request body for {@code POST /api/assets/{id}/mode} (docs/DRONE-INFRA-PLAN.md I-e Stage 2's frozen
 * wire contract) — the flight mode to command the asset's aircraft into.
 *
 * @param mode the target mode name (e.g. {@code "Loiter"}, {@code "RTL"})
 */
public record SetModeRequest(String mode) {

    /**
     * @return the requested mode, guaranteed non-blank
     * @throws IllegalArgumentException if {@link #mode()} is null or blank (→ 400)
     */
    public String requireMode() {
        if (mode == null || mode.isBlank()) {
            throw new IllegalArgumentException("mode must not be blank");
        }
        return mode;
    }
}
