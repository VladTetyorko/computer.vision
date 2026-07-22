package com.drones.vision.application;

/**
 * Everything needed to turn a video file into a watchable, telemetry-emitting simulated drone in
 * one call (docs/CYCLES-PLAN.md §1b) — the zero-hardware entry point: a file path in, a registered
 * asset out.
 *
 * @param displayName human-readable name; {@code null}/blank derives one from {@code videoPath}'s
 *                     file name (extension stripped)
 * @param videoPath   absolute path to a local video file; must not be blank — further validation
 *                     (existence, regular file, readable) happens in {@link SimulationService},
 *                     not here, since it requires filesystem access, not just shape-checking
 * @param latitude     home-point latitude for the synthetic telemetry track, or {@code null} for
 *                     {@code SimulatedTelemetrySource}'s own default
 * @param longitude    home-point longitude for the synthetic telemetry track, or {@code null} for
 *                     {@code SimulatedTelemetrySource}'s own default
 * @param autoStart    whether to start streaming immediately after creating the asset
 */
public record SimulationSpec(String displayName, String videoPath, Double latitude, Double longitude,
                              boolean autoStart) {

    public SimulationSpec {
        if (videoPath == null || videoPath.isBlank()) {
            throw new IllegalArgumentException("SimulationSpec videoPath must not be blank");
        }
    }
}
