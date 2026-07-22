package com.drones.vision.api.dto;

import com.drones.vision.application.SimulationSpec;
import com.drones.vision.application.SimulationTransport;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Request body for {@code POST /api/simulations} — the one-call, zero-hardware simulation entry
 * point (docs/CYCLES-PLAN.md §0-1, §3).
 *
 * @param displayName human-readable name; may be {@code null}/blank, in which case {@code
 *                     SimulationService} derives one from {@code videoPath}'s file name
 * @param videoPath   absolute path to a local video file on the server; must not be blank
 * @param latitude    home-point latitude for the synthetic telemetry track; may be {@code null}
 * @param longitude   home-point longitude for the synthetic telemetry track; may be {@code null}
 * @param autoStart   whether to start streaming immediately; {@code null}/absent defaults to
 *                     {@code true} — most callers simulating a drone want to watch it right away
 * @param transport   {@code "direct"} (in-process playback) or {@code "rtsp"} (pushed over the
 *                     wire and ingested back, docs/CYCLES-PLAN.md §3), matched case-insensitively;
 *                     {@code null}/absent defaults to {@code "direct"} — today's behavior
 */
public record StartSimulationRequest(String displayName, String videoPath, Double latitude, Double longitude,
                                      Boolean autoStart, String transport) {

    /**
     * Converts this request into a {@link SimulationSpec}, defaulting {@link #autoStart()} to
     * {@code true} and {@link #transport()} to {@link SimulationTransport#DIRECT} when absent.
     *
     * @return the input for {@code SimulationService#simulate}
     * @throws IllegalArgumentException if {@link #videoPath()} is blank, or {@link #transport()}
     *                                   doesn't match a known {@link SimulationTransport} name
     */
    public SimulationSpec toSpec() {
        return new SimulationSpec(displayName, videoPath, latitude, longitude, autoStart == null || autoStart,
                parseTransport(transport));
    }

    /**
     * Case-insensitive lookup against {@link SimulationTransport} names, {@code null}/blank
     * defaulting to {@link SimulationTransport#DIRECT} — mirrors {@code CapabilityParsing}'s
     * idiom of listing every valid value in the error message for an unrecognized one.
     */
    private static SimulationTransport parseTransport(String raw) {
        if (raw == null || raw.isBlank()) {
            return SimulationTransport.DIRECT;
        }
        for (SimulationTransport candidate : SimulationTransport.values()) {
            if (candidate.name().equalsIgnoreCase(raw)) {
                return candidate;
            }
        }
        throw new IllegalArgumentException("Unknown transport: " + raw + " (valid values: "
                + Arrays.stream(SimulationTransport.values()).map(Enum::name).collect(Collectors.joining(", "))
                + ")");
    }
}
