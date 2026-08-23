package com.drones.vision.api.support.afteraction;

import com.drones.vision.events.application.ReplayServiceSettings;

/**
 * Framework-free tunables for the after-action package (docs/plans/done/AFTER-ACTION-PLAN.md D8):
 * no {@code @ConfigurationProperties} here — {@code vision-api} may not depend on {@code
 * vision-app} — mirroring {@code OnboardingProperties}/{@code VisionApiProperties}'s own "plain
 * settings record, {@code vision-app} binds a Spring mirror onto an instance of this" bridge.
 *
 * @param maxPoints the ceiling requested from {@code ReplayService#timeline} (D7); a
 *                           returned telemetry count equal to this value means the source series
 *                           was thinned. Must be positive
 * @param auditLimit         maximum audit rows fetched for the {@code audit} part; must be positive
 */
public record AfterActionProperties(int maxPoints, int auditLimit) {

    public AfterActionProperties {
        if (maxPoints <= 0) {
            throw new IllegalArgumentException("maxPoints must be positive: " + maxPoints);
        }
        if (auditLimit <= 0) {
            throw new IllegalArgumentException("auditLimit must be positive: " + auditLimit);
        }
    }

    /**
     * {@code maxPoints} is a derived reference to {@code
     * ReplayServiceSettings#maxPointsCeiling()}'s own default (2000) — the same single source of
     * truth D7's thinning-detection heuristic relies on, not a second independent literal.
     * {@code vision-app}'s wiring passes the live-configured ceiling instead of this default
     * whenever {@code vision.application.replay.max-points-ceiling} is overridden; this factory
     * exists for tests and for a from-scratch default.
     *
     * @return {@code (2000, 200)}
     */
    public static AfterActionProperties defaults() {
        return new AfterActionProperties(ReplayServiceSettings.defaults().maxPointsCeiling(), 200);
    }
}
