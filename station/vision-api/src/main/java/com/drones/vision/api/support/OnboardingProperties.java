package com.drones.vision.api.support;

import java.time.Duration;
import java.util.Objects;

/**
 * Framework-free tunables for the onboarding PROBE stage's timing (docs/plans/active/
 * DRONE-ONBOARDING-PLAN.md §8.1/O5), the same "plain settings record, {@code vision-app} binds its
 * Spring-{@code @ConfigurationProperties} mirror onto an instance of this" bridge {@link
 * VisionApiProperties}'s own javadoc documents in full — {@code vision-api} may not depend on {@code
 * vision-app}, so its own {@code Configuration}-annotated {@code VisionOnboardingProperties} cannot
 * be referenced here directly.
 *
 * @param inventoryWindow how long a PROBE listens to the link before summarizing the passive
 *                        message inventory — passed straight through to {@code
 *                        VehicleProfileService#probe}/{@code #probeCandidate}
 * @param requestTimeout  per-request timeout for one {@code REQUEST_MESSAGE}/parameter read during a
 *                        probe; not consumed by anything in this module yet (mirrors {@code
 *                        VisionOnboardingProperties.Probe#requestTimeout}'s own not-yet-threaded
 *                        status) — carried here so the property has one place to land on this side
 *                        of the bridge once a consumer exists
 */
public record OnboardingProperties(Duration inventoryWindow, Duration requestTimeout) {

    public OnboardingProperties {
        Objects.requireNonNull(inventoryWindow, "inventoryWindow must not be null");
        Objects.requireNonNull(requestTimeout, "requestTimeout must not be null");
        if (inventoryWindow.isNegative() || inventoryWindow.isZero()) {
            throw new IllegalArgumentException("inventoryWindow must be positive: " + inventoryWindow);
        }
        if (requestTimeout.isNegative() || requestTimeout.isZero()) {
            throw new IllegalArgumentException("requestTimeout must be positive: " + requestTimeout);
        }
    }

    /** Mirrors {@code VisionOnboardingProperties.DEFAULT_INVENTORY_WINDOW}/{@code DEFAULT_REQUEST_TIMEOUT}. */
    public static OnboardingProperties defaults() {
        return new OnboardingProperties(Duration.ofSeconds(10), Duration.ofSeconds(3));
    }
}
