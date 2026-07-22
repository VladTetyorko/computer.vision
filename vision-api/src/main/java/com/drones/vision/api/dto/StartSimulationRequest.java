package com.drones.vision.api.dto;

import com.drones.vision.application.SimulationSpec;

/**
 * Request body for {@code POST /api/simulations} — the one-call, zero-hardware simulation entry
 * point (docs/CYCLES-PLAN.md §0-1).
 *
 * @param displayName human-readable name; may be {@code null}/blank, in which case {@code
 *                     SimulationService} derives one from {@code videoPath}'s file name
 * @param videoPath   absolute path to a local video file on the server; must not be blank
 * @param latitude    home-point latitude for the synthetic telemetry track; may be {@code null}
 * @param longitude   home-point longitude for the synthetic telemetry track; may be {@code null}
 * @param autoStart   whether to start streaming immediately; {@code null}/absent defaults to
 *                     {@code true} — most callers simulating a drone want to watch it right away
 */
public record StartSimulationRequest(String displayName, String videoPath, Double latitude, Double longitude,
                                      Boolean autoStart) {

    /**
     * Converts this request into a {@link SimulationSpec}, defaulting {@link #autoStart()} to
     * {@code true} when absent.
     *
     * @return the input for {@code SimulationService#simulate}
     * @throws IllegalArgumentException if {@link #videoPath()} is blank
     */
    public SimulationSpec toSpec() {
        return new SimulationSpec(displayName, videoPath, latitude, longitude, autoStart == null || autoStart);
    }
}
