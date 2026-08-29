package com.drones.vision.app.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Idle-usage-close sweep configuration ({@code vision.usage.*},
 * docs/plans/active/OPERATOR-UX-5-PLAN.md finding U1, wave W1) — a usage nobody explicitly
 * stopped (a crashed process, a station restart, a lost MAVLink link on an offline rover) used to
 * stay open forever, since {@code DefaultStreamService#stop} (vision-perception) is the only path
 * that ever ends one and no source failure alone ever calls it. {@link
 * com.drones.vision.app.usage.UsageIdleCloseRunner} sweeps every open usage once at startup — so a
 * crashed station never restarts with ghosts — and on {@link #sweepPeriod()} thereafter, closing any
 * whose last activity is older than {@link #idleClose()}
 * (docs/plans/active/OPERATOR-UX-5-PLAN.md's {@code IdleUsageCloseSettings}, vision-warehouse).
 *
 * <p>Unconditional, no enable flag: this fixes a data-correctness defect (a session claiming to still
 * be flying days after it went dark), not an optional feature.
 *
 * @param idleClose   how long an open usage may go with no observed activity before the sweep closes
 *                     it; must be positive; default {@value #DEFAULT_IDLE_CLOSE}
 * @param sweepPeriod how often the sweep runs after its immediate startup pass; must be positive;
 *                     default {@value #DEFAULT_SWEEP_PERIOD}
 */
@ConfigurationProperties(prefix = "vision.usage")
public record VisionUsageProperties(@DefaultValue(VisionUsageProperties.DEFAULT_IDLE_CLOSE) Duration idleClose,
                                     @DefaultValue(VisionUsageProperties.DEFAULT_SWEEP_PERIOD) Duration sweepPeriod) {

    static final String DEFAULT_IDLE_CLOSE = "10m";
    static final String DEFAULT_SWEEP_PERIOD = "60s";

    public VisionUsageProperties {
        if (idleClose == null || idleClose.isNegative() || idleClose.isZero()) {
            throw new IllegalArgumentException("vision.usage.idle-close must be positive: " + idleClose);
        }
        if (sweepPeriod == null || sweepPeriod.isNegative() || sweepPeriod.isZero()) {
            throw new IllegalArgumentException("vision.usage.sweep-period must be positive: " + sweepPeriod);
        }
    }
}
