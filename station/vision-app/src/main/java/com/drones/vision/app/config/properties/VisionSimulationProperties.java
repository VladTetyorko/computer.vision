package com.drones.vision.app.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Configuration for simulated-feed resume-on-boot ({@code vision.simulation.*}) — see {@code
 * com.drones.vision.app.bootstrap.SimulationResumeRunner} — extended by docs/plans/active/LAYERING-REFACTOR-PLAN.md
 * §2.2 (wave F4) with {@code adapter-simulation}'s synthetic video/telemetry generation tunables.
 *
 * <p>{@link #video()} maps onto {@code com.drones.vision.adapter.simulation.VideoSettings}; {@link
 * #telemetry()} maps onto {@code com.drones.vision.adapter.simulation.TelemetrySettings} — both
 * threaded into {@code SimulatedVideoSource}/{@code SimulatedTelemetrySource}'s constructors by
 * {@code wiring.VideoSourceWiring}/{@code wiring.TelemetryWiring}.
 *
 * @param resumeOnBoot whether to call {@code SimulationService#resumeAll()} once at boot (only
 *                     when {@code VisionPersistenceProperties#enabled()} is also {@code true} —
 *                     the in-memory profile has nothing to resume after a restart either, since
 *                     its assets are gone too); default {@code true}
 * @param video        {@code SimulatedVideoSource}'s default frame width/height/fps; defaulted as a
 *                     whole when absent
 * @param telemetry    {@code SimulatedTelemetrySource}'s default circular-track center/radius/cadence/
 *                     battery-drain; defaulted as a whole when absent
 * @param enabled      the Playground's own master switch (docs/plans/active/LINK-PAIRING-PLAN.md
 *                     §4 row L2) — gates {@code POST /api/simulations} ({@code SimulationController})
 *                     and the simulation discovery producers ({@code VideoSourceWiring}/{@code
 *                     TelemetryWiring}'s simulated-source beans); default {@code false}. Deliberately
 *                     independent of {@link #resumeOnBoot}, which only controls what an *already
 *                     existing* simulated asset does at boot — {@code false} here must still refuse
 *                     creating a *new* one, on a server where the feature was never turned on at all.
 */
@ConfigurationProperties(prefix = "vision.simulation")
public record VisionSimulationProperties(@DefaultValue("true") boolean resumeOnBoot, Video video, Telemetry telemetry,
                                          @DefaultValue("false") boolean enabled) {

    @ConstructorBinding
    public VisionSimulationProperties {
        if (video == null) {
            video = new Video(Video.DEFAULT_WIDTH_INT, Video.DEFAULT_HEIGHT_INT, Video.DEFAULT_FPS_INT);
        }
        if (telemetry == null) {
            telemetry = new Telemetry(Telemetry.DEFAULT_CENTER_LATITUDE_DOUBLE,
                    Telemetry.DEFAULT_CENTER_LONGITUDE_DOUBLE, Telemetry.DEFAULT_TRACK_RADIUS_METERS_DOUBLE,
                    Telemetry.DEFAULT_PERIOD_DURATION, Telemetry.DEFAULT_BATTERY_DRAIN_PERCENT_PER_SECOND_DOUBLE);
        }
    }

    /**
     * Convenience constructor covering just the original {@code vision.simulation.resume-on-boot}
     * field (predating wave F4's {@code video}/{@code telemetry} extension, and LINK-PAIRING's
     * {@code enabled}) — both nested records default exactly as they would from an absent binding,
     * and {@code enabled} defaults {@code false}.
     */
    public VisionSimulationProperties(boolean resumeOnBoot) {
        this(resumeOnBoot, null, null, false);
    }

    /**
     * @param width  default frame width in pixels; default {@value #DEFAULT_WIDTH}
     * @param height default frame height in pixels; default {@value #DEFAULT_HEIGHT}
     * @param fps    default target frames per second; default {@value #DEFAULT_FPS}
     */
    public record Video(@DefaultValue(Video.DEFAULT_WIDTH) int width,
                        @DefaultValue(Video.DEFAULT_HEIGHT) int height,
                        @DefaultValue(Video.DEFAULT_FPS) int fps) {
        static final String DEFAULT_WIDTH = "640";
        static final String DEFAULT_HEIGHT = "480";
        static final String DEFAULT_FPS = "15";
        static final int DEFAULT_WIDTH_INT = 640;
        static final int DEFAULT_HEIGHT_INT = 480;
        static final int DEFAULT_FPS_INT = 15;
    }

    /**
     * @param centerLatitude               default circular-track center latitude; default {@value #DEFAULT_CENTER_LATITUDE}
     * @param centerLongitude              default circular-track center longitude; default {@value #DEFAULT_CENTER_LONGITUDE}
     * @param trackRadiusMeters            circular-track radius in meters; default {@value #DEFAULT_TRACK_RADIUS_METERS}
     * @param period                       time between samples; default 1s (1 Hz)
     * @param batteryDrainPercentPerSecond default battery drain rate, percent/second; default {@value #DEFAULT_BATTERY_DRAIN_PERCENT_PER_SECOND}
     */
    public record Telemetry(@DefaultValue(Telemetry.DEFAULT_CENTER_LATITUDE) double centerLatitude,
                             @DefaultValue(Telemetry.DEFAULT_CENTER_LONGITUDE) double centerLongitude,
                             @DefaultValue(Telemetry.DEFAULT_TRACK_RADIUS_METERS) double trackRadiusMeters,
                             @DefaultValue("1s") Duration period,
                             @DefaultValue(Telemetry.DEFAULT_BATTERY_DRAIN_PERCENT_PER_SECOND) double batteryDrainPercentPerSecond) {
        static final String DEFAULT_CENTER_LATITUDE = "50.45";
        static final String DEFAULT_CENTER_LONGITUDE = "30.52";
        static final String DEFAULT_TRACK_RADIUS_METERS = "200.0";
        static final String DEFAULT_BATTERY_DRAIN_PERCENT_PER_SECOND = "0.05";
        static final double DEFAULT_CENTER_LATITUDE_DOUBLE = 50.45;
        static final double DEFAULT_CENTER_LONGITUDE_DOUBLE = 30.52;
        static final double DEFAULT_TRACK_RADIUS_METERS_DOUBLE = 200.0;
        static final Duration DEFAULT_PERIOD_DURATION = Duration.ofSeconds(1);
        static final double DEFAULT_BATTERY_DRAIN_PERCENT_PER_SECOND_DOUBLE = 0.05;
    }
}
