package com.drones.vision.app.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Telemetry flow policy ({@code vision.telemetry.*}, docs/plans/active/ALWAYS-ON-FLOW-PLAN.md wave A).
 *
 * <p><b>Its own root, deliberately not under {@code vision.streams.*}.</b> Those keys decide whether
 * a <em>video stream</em> should exist; these decide whether a <em>link</em> should be claimed. The
 * whole point of wave A is that the two are different questions — conflating them is what let an
 * idle-stream sweep silently end an aircraft's telemetry.
 *
 * @param alwaysOn whether telemetry is claimed for every in-service asset, independent of video
 */
@ConfigurationProperties(prefix = "vision.telemetry")
public record VisionTelemetryProperties(@DefaultValue AlwaysOn alwaysOn) {

    public VisionTelemetryProperties {
        alwaysOn = alwaysOn == null ? new AlwaysOn(false, null) : alwaysOn;
    }

    /**
     * @param enabled       whether {@code TelemetryPinRunner} claims telemetry for every in-service
     *                      asset. <b>Defaults off</b>, matching this repo's rule that a new
     *                      behaviour ships invisible: with it false no runner is scheduled, nothing
     *                      is pinned, and telemetry opens exactly where it always did — a device's
     *                      first video stream, or an operator {@code engage}. Turned on in
     *                      {@code docker-compose.yml}, which is where the run is described
     * @param sweepInterval how often the desired pin set is reconciled against what is open;
     *                      default {@value #DEFAULT_SWEEP_INTERVAL}. One asset listing per tick,
     *                      plus an idempotent pin call per in-service asset — both in-memory once
     *                      the sources are open, so this is cheap. It is a convergence loop, not a
     *                      latency budget: a newly registered aircraft's link comes up within one
     *                      sweep, which is well inside the time it takes an operator to walk to it
     */
    public record AlwaysOn(@DefaultValue("false") boolean enabled,
                            @DefaultValue(AlwaysOn.DEFAULT_SWEEP_INTERVAL) Duration sweepInterval) {

        static final String DEFAULT_SWEEP_INTERVAL = "30s";

        public AlwaysOn {
            sweepInterval = sweepInterval == null ? Duration.parse("PT30S") : sweepInterval;
            if (sweepInterval.isNegative() || sweepInterval.isZero()) {
                throw new IllegalArgumentException("vision.telemetry.always-on.sweep-interval must be positive");
            }
        }
    }
}
