package com.drones.vision.app;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuration for simulated-feed resume-on-boot ({@code vision.simulation.*}) — see {@link
 * SimulationResumeRunner}.
 *
 * @param resumeOnBoot whether to call {@code SimulationService#resumeAll()} once at boot (only
 *                      when {@link VisionPersistenceProperties#enabled()} is also {@code true} —
 *                      the in-memory profile has nothing to resume after a restart either, since
 *                      its assets are gone too); default {@code true}
 */
@ConfigurationProperties(prefix = "vision.simulation")
public record VisionSimulationProperties(@DefaultValue("true") boolean resumeOnBoot) {
}
