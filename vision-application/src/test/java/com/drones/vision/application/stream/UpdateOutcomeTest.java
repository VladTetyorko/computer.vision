package com.drones.vision.application.stream;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpdateOutcomeTest {

    @Test
    void reportsExactlyWhatItWasGiven() {
        assertTrue(new UpdateOutcome(true).modelReArmed());
        assertFalse(new UpdateOutcome(false).modelReArmed());
    }
}
