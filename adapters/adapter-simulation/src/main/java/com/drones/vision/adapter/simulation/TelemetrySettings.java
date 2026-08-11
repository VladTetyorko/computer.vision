package com.drones.vision.adapter.simulation;

/**
 * Framework-free tunables for {@link SimulatedTelemetrySource}'s synthetic telemetry generation:
 * the circular-track center and radius, the sample cadence, and the default battery drain rate.
 *
 * <p>{@code centerLatitude}/{@code centerLongitude}/{@code batteryDrainPercentPerSecond} are the
 * <em>fallback</em> values a {@link SimulatedTelemetrySource} instance uses whenever a device's own
 * {@code lat}/{@code lon}/{@code batteryDrainPerSecond} options are absent — a per-{@code open()}
 * option always takes priority (see {@link SimulatedTelemetrySource} for that lenient-parsing
 * convention). {@code trackRadiusMeters} and {@code periodMillis} are not exposed as per-device
 * options at all; they are fixed for the life of a {@link SimulatedTelemetrySource} instance. Per
 * docs/plans/active/LAYERING-REFACTOR-PLAN.md §1.3, this module never imports a {@code @ConfigurationProperties}
 * type; a later wave binds {@code vision.simulation.telemetry.*} in {@code vision-app} and
 * constructs this record there.
 *
 * @param centerLatitude               default circular-track center latitude
 * @param centerLongitude              default circular-track center longitude
 * @param trackRadiusMeters            circular-track radius in meters; must be positive
 * @param periodMillis                 milliseconds between samples; must be positive
 * @param batteryDrainPercentPerSecond default battery drain rate, percent/second from a full charge
 */
public record TelemetrySettings(double centerLatitude, double centerLongitude, double trackRadiusMeters,
                                 long periodMillis, double batteryDrainPercentPerSecond) {

    static final double DEFAULT_CENTER_LATITUDE = 50.45;
    static final double DEFAULT_CENTER_LONGITUDE = 30.52;
    static final double DEFAULT_TRACK_RADIUS_METERS = 200.0;
    static final long DEFAULT_PERIOD_MILLIS = 1000L; // 1 Hz
    static final double DEFAULT_BATTERY_DRAIN_PERCENT_PER_SECOND = 0.05;

    public TelemetrySettings {
        if (trackRadiusMeters <= 0) {
            throw new IllegalArgumentException("trackRadiusMeters must be positive: " + trackRadiusMeters);
        }
        if (periodMillis <= 0) {
            throw new IllegalArgumentException("periodMillis must be positive: " + periodMillis);
        }
    }

    /**
     * The pre-extraction defaults (1 Hz, center 50.45/30.52, 200m radius, 0.05%/s drain) — byte-identical
     * to the literals they replace.
     */
    public static TelemetrySettings defaults() {
        return new TelemetrySettings(DEFAULT_CENTER_LATITUDE, DEFAULT_CENTER_LONGITUDE,
                DEFAULT_TRACK_RADIUS_METERS, DEFAULT_PERIOD_MILLIS, DEFAULT_BATTERY_DRAIN_PERCENT_PER_SECOND);
    }

    /** Test/config seam: same values but an arbitrary sample period. */
    TelemetrySettings withPeriodMillis(long periodMillis) {
        return new TelemetrySettings(centerLatitude, centerLongitude, trackRadiusMeters,
                periodMillis, batteryDrainPercentPerSecond);
    }
}
