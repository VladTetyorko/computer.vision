package com.drones.vision.perception.application.stream;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpdateOutcomeTest {

    @Test
    void reportsExactlyWhatItWasGiven() {
        assertTrue(new UpdateOutcome(true).modelReArmed());
        assertFalse(new UpdateOutcome(false).modelReArmed());
        assertTrue(new UpdateOutcome(false, true).trackingChanged());
        assertFalse(new UpdateOutcome(true, false).trackingChanged());
    }

    @Test
    void theTwoFlagsAreIndependentBecauseTrackingNeverReArmsTheDetector() {
        assertTrue(new UpdateOutcome(true, true).modelReArmed());
        assertTrue(new UpdateOutcome(true, true).trackingChanged());
    }

    @Test
    void theOneArgConvenienceConstructorMeansTrackingWasUntouched() {
        assertFalse(new UpdateOutcome(true).trackingChanged());
    }
}
