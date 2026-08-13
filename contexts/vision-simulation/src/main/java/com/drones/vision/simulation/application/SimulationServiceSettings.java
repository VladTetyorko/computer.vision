package com.drones.vision.simulation.application;

import java.util.Objects;

/**
 * Tunables for {@link DefaultSimulationService}'s MAVLink-transport fallback defaults — extracted
 * per docs/plans/active/LAYERING-REFACTOR-PLAN.md &sect;1.3's config-extraction rule. Framework-free; {@code
 * vision-app} binds a {@code VisionApplicationProperties} record and maps it onto this record's
 * constructor. Every {@link #defaults()} value is byte-identical to the literal it replaces.
 *
 * @param mavlinkLoopbackHost loopback host every MAVLink-transport simulation's telemetry feed
 *                             binds/pushes to; must not be blank
 * @param fallbackLatitude    default circular-track center latitude used when a MAVLink-transport
 *                             simulation has no explicit {@code lat}/{@code lon}/plan, mirroring
 *                             {@code SimulatedTelemetrySource}'s (adapter-simulation) own default
 * @param fallbackLongitude   default circular-track center longitude, paired with {@link
 *                             #fallbackLatitude}
 */
public record SimulationServiceSettings(String mavlinkLoopbackHost, double fallbackLatitude,
                                         double fallbackLongitude) {

    public SimulationServiceSettings {
        Objects.requireNonNull(mavlinkLoopbackHost, "mavlinkLoopbackHost must not be null");
        if (mavlinkLoopbackHost.isBlank()) {
            throw new IllegalArgumentException("mavlinkLoopbackHost must not be blank");
        }
    }

    /** Every value byte-identical to the literal it replaces (docs/plans/active/LAYERING-REFACTOR-PLAN.md &sect;1.3). */
    public static SimulationServiceSettings defaults() {
        return new SimulationServiceSettings("127.0.0.1", 50.45, 30.52);
    }
}
