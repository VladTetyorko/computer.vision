package com.drones.vision.adapter.discovery.mdns;

import java.time.Duration;
import java.util.Objects;

/**
 * Timeout-budget cushions {@link MdnsScanner} carves <em>out of</em> its
 * caller-requested {@link MdnsScanner#scan(Duration) scan(timeout)} window,
 * rather than adding on top of it, so that {@code JmDNS} setup, the two
 * concurrent {@code list()} calls and their join all fit inside the
 * deadline. Extracted from {@code private static final long} literals per
 * {@code docs/plans/active/LAYERING-REFACTOR-PLAN.md} §1.3/§2.2 (property prefix {@code
 * vision.discovery.mdns}). Framework-free settings record — {@code
 * vision-app}'s wiring is expected to bind {@code vision.discovery.mdns.*}
 * onto an instance of this type (a later wave) and pass it to one of {@link
 * MdnsScanner}'s constructors; this module knows nothing of Spring or
 * configuration-properties binding.
 *
 * <p><b>Changing {@link #defaults()} changes scan-timing behavior</b> that
 * {@code MdnsScannerLoopbackTest#scanReturnsWithinTimeoutPlusCallerGrace} and
 * {@code DefaultDiscoveryService}'s 200ms caller grace (vision-application)
 * are both coupled to. Treat any change to the defaults as a deliberate,
 * separately reviewed retuning decision, not an incidental one.
 *
 * @param joinGrace     cushion reserved so {@link Thread#join} has a
 *                      realistic chance of observing the two browsing
 *                      threads finish, on top of {@code minListWindow};
 *                      must not be negative (was {@code JOIN_GRACE_MILLIS})
 * @param safetyMargin  further cushion, on top of {@code joinGrace}, to
 *                      absorb scheduling/JIT jitter and any measurement
 *                      error in the {@code JmDNS.create()} setup-time
 *                      accounting; must not be negative (was {@code
 *                      SAFETY_MARGIN_MILLIS})
 * @param minListWindow floor for the {@code list()} window itself, so an
 *                      already very tight timeout (mostly or entirely
 *                      consumed by setup) still gives the browse a minimal
 *                      chance to run rather than a zero/negative one; must
 *                      not be negative (was {@code MIN_LIST_WINDOW_MILLIS})
 */
public record ScanBudget(Duration joinGrace, Duration safetyMargin, Duration minListWindow) {

    private static final Duration DEFAULT_JOIN_GRACE = Duration.ofMillis(150);
    private static final Duration DEFAULT_SAFETY_MARGIN = Duration.ofMillis(50);
    private static final Duration DEFAULT_MIN_LIST_WINDOW = Duration.ofMillis(50);

    public ScanBudget {
        Objects.requireNonNull(joinGrace, "joinGrace must not be null");
        Objects.requireNonNull(safetyMargin, "safetyMargin must not be null");
        Objects.requireNonNull(minListWindow, "minListWindow must not be null");
        if (joinGrace.isNegative()) {
            throw new IllegalArgumentException("joinGrace must not be negative: " + joinGrace);
        }
        if (safetyMargin.isNegative()) {
            throw new IllegalArgumentException("safetyMargin must not be negative: " + safetyMargin);
        }
        if (minListWindow.isNegative()) {
            throw new IllegalArgumentException("minListWindow must not be negative: " + minListWindow);
        }
    }

    /**
     * Today's hardcoded literals, unchanged: 150ms join grace, 50ms safety
     * margin, 50ms minimum list window. Byte-identical to the {@code
     * private static final long} constants this record replaces.
     */
    public static ScanBudget defaults() {
        return new ScanBudget(DEFAULT_JOIN_GRACE, DEFAULT_SAFETY_MARGIN, DEFAULT_MIN_LIST_WINDOW);
    }

    long joinGraceMillis() {
        return joinGrace.toMillis();
    }

    long safetyMarginMillis() {
        return safetyMargin.toMillis();
    }

    long minListWindowMillis() {
        return minListWindow.toMillis();
    }
}
