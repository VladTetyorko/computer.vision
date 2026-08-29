package com.drones.vision.warehouse.application.usage;

import java.time.Duration;

/**
 * The one tunable {@link UsageIdleCloseService} needs — how long an open
 * {@link com.drones.vision.warehouse.domain.model.AssetUsage} may go with no observed activity
 * before {@link DefaultUsageIdleCloseService#closeIdleUsages()} closes it
 * (docs/plans/active/OPERATOR-UX-5-PLAN.md finding U1, wave W1; {@code vision-app}'s {@code
 * VisionUsageProperties} binds {@code vision.usage.idle-close} onto this).
 *
 * <p>Bundled into its own record per {@code java-clean-code} §3 ("a new collaborator means updating
 * the call sites, or bundling into a settings record — never one more constructor overload") rather
 * than a bare constructor parameter on {@link DefaultUsageIdleCloseService}, so a future tunable
 * joins this record instead of growing that constructor.
 *
 * @param idleThreshold how long an open usage's last activity may age before the sweep closes it;
 *                       must be positive
 */
public record IdleUsageCloseSettings(Duration idleThreshold) {

    /** {@code vision.usage.idle-close}'s documented default in root {@code application.yaml}. */
    private static final Duration DEFAULT_IDLE_THRESHOLD = Duration.ofMinutes(10);

    public IdleUsageCloseSettings {
        if (idleThreshold == null || idleThreshold.isNegative() || idleThreshold.isZero()) {
            throw new IllegalArgumentException(
                    "IdleUsageCloseSettings idleThreshold must be positive: " + idleThreshold);
        }
    }

    /**
     * Ten minutes — the same default {@code vision.usage.idle-close} documents in root {@code
     * application.yaml}; used by tests and by any caller that has no reason to configure this
     * explicitly.
     */
    public static IdleUsageCloseSettings defaults() {
        return new IdleUsageCloseSettings(DEFAULT_IDLE_THRESHOLD);
    }
}
