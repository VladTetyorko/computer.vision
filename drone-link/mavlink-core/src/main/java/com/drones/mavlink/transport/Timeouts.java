package com.drones.mavlink.transport;

import java.time.Duration;
import java.util.Objects;

/**
 * Converts a {@link Duration} into a socket-timeout millisecond value, guarding against the one
 * sharp edge every {@code java.net} socket shares: {@code setSoTimeout(0)} means "block forever,"
 * the opposite of what a caller passing {@link Duration#ZERO} (or a negative duration) almost
 * certainly means ("don't wait"). Clamped to at least 1ms instead, so {@link MavlinkLink#poll} of
 * a non-positive duration behaves as an effectively-immediate poll rather than an infinite block.
 */
final class Timeouts {

    private Timeouts() {
    }

    static int clampMillis(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        long millis = timeout.toMillis();
        return (int) Math.min(Integer.MAX_VALUE, Math.max(1, millis));
    }
}
