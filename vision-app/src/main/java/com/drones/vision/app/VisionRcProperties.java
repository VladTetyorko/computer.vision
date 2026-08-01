package com.drones.vision.app;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuration for the RC manual-control relay ({@code vision.rc.*}), docs/RC-CONTROL-PHASE1-PLAN.md
 * §2/§4.
 *
 * <p>Read by {@link WiringConfiguration#manualControlService} to size {@code
 * DefaultManualControlService}'s input-loss watchdog (the canonical 6-arg constructor's explicit
 * {@code watchdogTimeoutMs} argument). {@code vision-api}'s {@code ManualControlWebSocketHandler}
 * reads the same {@code vision.rc.watchdog-timeout-ms} property key independently, via {@code
 * @Value} rather than this record (vision-api may not depend on vision-app) — see that class's own
 * javadoc; both readers stay in sync because they read the identical property key.
 *
 * @param watchdogTimeoutMs input-loss watchdog timeout in milliseconds: a session with no {@code
 *                          channels} frame for this long auto-releases; must be positive; default
 *                          {@value #DEFAULT_WATCHDOG_TIMEOUT_MS}, matching {@code
 *                          DefaultManualControlService.DEFAULT_WATCHDOG_TIMEOUT_MS}
 */
@ConfigurationProperties(prefix = "vision.rc")
public record VisionRcProperties(
        @DefaultValue(VisionRcProperties.DEFAULT_WATCHDOG_TIMEOUT_MS) long watchdogTimeoutMs) {

    static final String DEFAULT_WATCHDOG_TIMEOUT_MS = "300";

    public VisionRcProperties {
        if (watchdogTimeoutMs <= 0) {
            throw new IllegalArgumentException(
                    "vision.rc.watchdog-timeout-ms must be positive, was " + watchdogTimeoutMs);
        }
    }
}
