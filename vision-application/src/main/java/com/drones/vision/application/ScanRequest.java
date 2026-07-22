package com.drones.vision.application;

import java.time.Duration;
import java.util.Set;

/**
 * What a discovery scan should do.
 *
 * @param timeout how long to wait for responses; must be positive
 * @param methods which discovery methods to run; empty means "all registered"
 */
public record ScanRequest(Duration timeout, Set<String> methods) {

    /** Four seconds, every registered method — long enough for mDNS, short enough to stay interactive. */
    public static ScanRequest defaults() {
        return new ScanRequest(Duration.ofSeconds(4), Set.of());
    }

    public ScanRequest {
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("ScanRequest timeout must be positive: " + timeout);
        }
        if (methods == null) {
            throw new IllegalArgumentException("ScanRequest methods must not be null");
        }
        methods = Set.copyOf(methods);
    }
}
