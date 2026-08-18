package com.drones.vision.flight.domain.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ParameterDriftTest {

    private static final Instant EARLIER = Instant.parse("2026-08-18T08:00:00Z");
    private static final Instant LATER = Instant.parse("2026-08-18T09:00:00Z");

    @Test
    void blankParameterNameIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new ParameterDrift(" ", 0.0, 1.0, EARLIER, LATER));
    }

    @Test
    void currentObservedAtBeforePreviousObservedAtIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new ParameterDrift("SR2_EXTRA2", 0.0, 1.0, LATER, EARLIER));
    }

    @Test
    void equalPreviousAndCurrentValuesAreRejectedBecauseANonChangeIsNotADrift() {
        assertThrows(IllegalArgumentException.class,
                () -> new ParameterDrift("SR2_EXTRA2", 1.0, 1.0, EARLIER, LATER));
    }

    @Test
    void aGenuineChangeConstructsCleanly() {
        ParameterDrift drift = new ParameterDrift("SR2_EXTRA2", 0.0, 1.0, EARLIER, LATER);
        assertEquals("SR2_EXTRA2", drift.parameterName());
        assertEquals(0.0, drift.previousValue());
        assertEquals(1.0, drift.currentValue());
    }
}
