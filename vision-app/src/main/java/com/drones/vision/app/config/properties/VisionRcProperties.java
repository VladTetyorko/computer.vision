package com.drones.vision.app.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuration for the RC manual-control relay ({@code vision.rc.*}), docs/plans/done/RC-CONTROL-PHASE1-PLAN.md
 * §2/§4, extended by docs/plans/active/LAYERING-REFACTOR-PLAN.md §2.2 (wave F2) with the cadence values that used
 * to be the {@code VISION_RC_OVERRIDE_HZ}/{@code VISION_RC_RELEASE_FRAMES} environment variables —
 * the one place in the repo that bypassed Spring config entirely, now closed.
 *
 * <p>Read by {@code wiring.ApplicationServiceWiring#manualControlService} to size {@code
 * DefaultManualControlService}'s input-loss watchdog (the canonical 6-arg constructor's explicit
 * {@code watchdogTimeoutMs} argument). {@code vision-api}'s {@code ManualControlWebSocketHandler}
 * reads the same {@code vision.rc.watchdog-timeout-ms} property key independently, via {@code
 * @Value} rather than this record (vision-api may not depend on vision-app) — see that class's own
 * javadoc; both readers stay in sync because they read the identical property key.
 *
 * <p>{@link #overrideHz()}/{@link #minOverrideHz()}/{@link #maxOverrideHz()}/{@link
 * #releaseFrames()} map onto {@code com.drones.vision.adapter.mavlink.MavlinkSettings.Rc}, threaded
 * into {@code MavlinkManualControlSender}'s constructor by {@code wiring.TelemetryWiring} — every
 * default below is byte-identical to what {@code MavlinkManualControlSender.DEFAULT_OVERRIDE_HZ}/
 * {@code MIN_OVERRIDE_HZ}/{@code MAX_OVERRIDE_HZ}/{@code DEFAULT_RELEASE_FRAMES} used to hardcode
 * before those env vars were deleted.
 *
 * @param watchdogTimeoutMs input-loss watchdog timeout in milliseconds: a session with no {@code
 *                          channels} frame for this long auto-releases; must be positive; default
 *                          {@value #DEFAULT_WATCHDOG_TIMEOUT_MS}, matching {@code
 *                          DefaultManualControlService.DEFAULT_WATCHDOG_TIMEOUT_MS}
 * @param overrideHz        {@code RC_CHANNELS_OVERRIDE} send rate; default {@value #DEFAULT_OVERRIDE_HZ}
 * @param minOverrideHz     lower clamp bound for {@link #overrideHz()}; default {@value #DEFAULT_MIN_OVERRIDE_HZ}
 * @param maxOverrideHz     upper clamp bound for {@link #overrideHz()}; default {@value #DEFAULT_MAX_OVERRIDE_HZ}
 * @param releaseFrames     release-burst tick count; default {@value #DEFAULT_RELEASE_FRAMES}
 */
@ConfigurationProperties(prefix = "vision.rc")
public record VisionRcProperties(
        @DefaultValue(VisionRcProperties.DEFAULT_WATCHDOG_TIMEOUT_MS) long watchdogTimeoutMs,
        @DefaultValue(VisionRcProperties.DEFAULT_OVERRIDE_HZ) int overrideHz,
        @DefaultValue(VisionRcProperties.DEFAULT_MIN_OVERRIDE_HZ) int minOverrideHz,
        @DefaultValue(VisionRcProperties.DEFAULT_MAX_OVERRIDE_HZ) int maxOverrideHz,
        @DefaultValue(VisionRcProperties.DEFAULT_RELEASE_FRAMES) int releaseFrames) {

    static final String DEFAULT_WATCHDOG_TIMEOUT_MS = "300";
    static final String DEFAULT_OVERRIDE_HZ = "33";
    static final String DEFAULT_MIN_OVERRIDE_HZ = "10";
    static final String DEFAULT_MAX_OVERRIDE_HZ = "50";
    static final String DEFAULT_RELEASE_FRAMES = "3";

    public VisionRcProperties {
        if (watchdogTimeoutMs <= 0) {
            throw new IllegalArgumentException(
                    "vision.rc.watchdog-timeout-ms must be positive, was " + watchdogTimeoutMs);
        }
        if (minOverrideHz <= 0 || maxOverrideHz < minOverrideHz) {
            throw new IllegalArgumentException("vision.rc.min-override-hz/max-override-hz out of order: "
                    + minOverrideHz + "/" + maxOverrideHz);
        }
        if (overrideHz <= 0) {
            throw new IllegalArgumentException("vision.rc.override-hz must be positive, was " + overrideHz);
        }
        if (releaseFrames <= 0) {
            throw new IllegalArgumentException("vision.rc.release-frames must be positive, was " + releaseFrames);
        }
    }
}
