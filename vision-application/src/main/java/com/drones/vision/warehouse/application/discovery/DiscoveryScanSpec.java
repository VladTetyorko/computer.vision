package com.drones.vision.warehouse.application.discovery;

import java.time.Duration;
import java.util.Set;

/**
 * What a discovery scan should do.
 *
 * @param timeout how long to wait for responses; must be positive
 * @param methods which discovery methods to run; empty means "all registered"
 */
public record DiscoveryScanSpec(Duration timeout, Set<String> methods) {

    /** Four seconds, every registered method — long enough for mDNS, short enough to stay interactive. */
    public static DiscoveryScanSpec defaults() {
        return new DiscoveryScanSpec(Duration.ofSeconds(4), Set.of());
    }

    public DiscoveryScanSpec {
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("DiscoveryScanSpec timeout must be positive: " + timeout);
        }
        if (methods == null) {
            throw new IllegalArgumentException("DiscoveryScanSpec methods must not be null");
        }
        methods = Set.copyOf(methods);
    }
}
