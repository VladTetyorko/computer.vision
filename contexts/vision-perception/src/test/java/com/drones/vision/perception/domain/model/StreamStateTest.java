package com.drones.vision.perception.domain.model;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins {@link StreamState#resolve}'s decision table and, more importantly, its <b>precedence</b> —
 * the part a reader cannot infer from the enum constants alone (docs/plans/active/STREAM-STATE-PLAN.md
 * &sect;2.1).
 */
class StreamStateTest {

    private static final long STALE_AFTER = Duration.ofSeconds(5).toNanos();

    @Test
    void proxiedStreamIsUnobservedRatherThanStarting() {
        // The trap this enum exists for: a proxied stream opens no source here, so framesObserved
        // stays 0 for its whole life. STARTING would be a lie that never resolves.
        assertEquals(StreamState.UNOBSERVED,
                StreamState.resolve(false, false, 0L, Long.MAX_VALUE, STALE_AFTER));
    }

    @Test
    void proxiedStreamStaysUnobservedEvenIfEveryOtherSignalWouldSayLive() {
        assertEquals(StreamState.UNOBSERVED, StreamState.resolve(false, false, 500L, 1L, STALE_AFTER));
    }

    @Test
    void startingWhileNoFrameHasArrivedYet() {
        assertEquals(StreamState.STARTING,
                StreamState.resolve(true, false, 0L, Long.MAX_VALUE, STALE_AFTER));
    }

    @Test
    void liveWhileFramesArriveInsideTheWindow() {
        assertEquals(StreamState.LIVE,
                StreamState.resolve(true, false, 42L, Duration.ofMillis(40).toNanos(), STALE_AFTER));
    }

    @Test
    void stalledOnceTheGapExceedsTheWindow() {
        assertEquals(StreamState.STALLED,
                StreamState.resolve(true, false, 42L, Duration.ofSeconds(6).toNanos(), STALE_AFTER));
    }

    @Test
    void exactlyAtTheThresholdIsStillLive() {
        // The comparison is strictly-greater, so the boundary belongs to LIVE. Pinned because a
        // reader cannot tell which side owns it, and flipping it would make a healthy source at
        // exactly the threshold flicker.
        assertEquals(StreamState.LIVE, StreamState.resolve(true, false, 42L, STALE_AFTER, STALE_AFTER));
    }

    @Test
    void reconnectingOutranksStalled() {
        // Both hold during an outage: frames stopped AND the supervisor is retrying. Reporting
        // STALLED would throw away the explanation and hide that recovery is already under way.
        assertEquals(StreamState.RECONNECTING,
                StreamState.resolve(true, true, 42L, Duration.ofMinutes(1).toNanos(), STALE_AFTER));
    }

    @Test
    void reconnectingOutranksStarting() {
        // A source that failed before delivering its first frame: framesObserved is still 0, but
        // "reconnecting" is the more informative of the two true statements.
        assertEquals(StreamState.RECONNECTING,
                StreamState.resolve(true, true, 0L, Long.MAX_VALUE, STALE_AFTER));
    }

    @Test
    void proxiedOutranksReconnecting() {
        // Cannot arise today (a proxied stream wires no supervisor, so `reconnecting` is passed
        // false by DefaultStreamService), but pinned so the total function has no undefined corner.
        assertEquals(StreamState.UNOBSERVED,
                StreamState.resolve(false, true, 0L, Long.MAX_VALUE, STALE_AFTER));
    }
}
