package com.drones.vision.perception.application.stream;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class IdleStreamPolicyTest {

    @Test
    void rejectsNonPositiveDurations() {
        assertThrows(IllegalArgumentException.class,
                () -> new IdleStreamPolicy(true, Duration.ZERO, Duration.ofSeconds(30)));
        assertThrows(IllegalArgumentException.class,
                () -> new IdleStreamPolicy(true, Duration.ofMinutes(10), Duration.ofSeconds(-1)));
        assertThrows(IllegalArgumentException.class,
                () -> new IdleStreamPolicy(true, null, Duration.ofSeconds(30)));
    }

    @Test
    void rejectsACheckIntervalCoarserThanTheTimeout() {
        assertThrows(IllegalArgumentException.class,
                () -> new IdleStreamPolicy(true, Duration.ofMinutes(10), Duration.ofMinutes(30)));
    }

    @Test
    void acceptsACheckIntervalEqualToTheTimeout() {
        IdleStreamPolicy policy = new IdleStreamPolicy(true, Duration.ofMinutes(10), Duration.ofMinutes(10));
        assertEquals(Duration.ofMinutes(10), policy.checkInterval());
    }

    @Test
    void disabledCarriesUsableDurationsSoItStillValidates() {
        IdleStreamPolicy policy = IdleStreamPolicy.disabled();
        assertFalse(policy.enabled());
    }
}
