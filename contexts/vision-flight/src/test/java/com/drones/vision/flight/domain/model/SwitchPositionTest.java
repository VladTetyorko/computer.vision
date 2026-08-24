package com.drones.vision.flight.domain.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SwitchPositionTest {

    @Test
    void aThreePositionSwitchOnAnAxisReadsAllThreeDetents() {
        assertEquals(SwitchPosition.LOW,
                SwitchPosition.of(ControlBinding.Source.AXIS, ControlInputKind.SWITCH_3, -1.0));
        assertEquals(SwitchPosition.MIDDLE,
                SwitchPosition.of(ControlBinding.Source.AXIS, ControlInputKind.SWITCH_3, 0.0));
        assertEquals(SwitchPosition.HIGH,
                SwitchPosition.of(ControlBinding.Source.AXIS, ControlInputKind.SWITCH_3, 1.0));
    }

    @Test
    void aTwoPositionSwitchNeverReportsMiddle() {
        assertEquals(SwitchPosition.LOW,
                SwitchPosition.of(ControlBinding.Source.AXIS, ControlInputKind.SWITCH_2, 0.0 - 1e-9));
        assertEquals(SwitchPosition.HIGH,
                SwitchPosition.of(ControlBinding.Source.AXIS, ControlInputKind.SWITCH_2, 0.0));
    }

    @Test
    void aButtonSourceIsPressedAtHalfTravel() {
        assertEquals(SwitchPosition.LOW,
                SwitchPosition.of(ControlBinding.Source.BUTTON, ControlInputKind.BUTTON, 0.49));
        assertEquals(SwitchPosition.HIGH,
                SwitchPosition.of(ControlBinding.Source.BUTTON, ControlInputKind.BUTTON, 0.5));
    }

    @Test
    void reversingMirrorsTheEndsAndLeavesTheMiddleAlone() {
        assertEquals(SwitchPosition.HIGH, SwitchPosition.LOW.reversed());
        assertEquals(SwitchPosition.LOW, SwitchPosition.HIGH.reversed());
        assertEquals(SwitchPosition.MIDDLE, SwitchPosition.MIDDLE.reversed());
    }

    @Test
    void auxFunctionLevelsAreArdupilotsOwnZeroOneTwo() {
        assertEquals(0, SwitchPosition.LOW.auxFunctionLevel());
        assertEquals(1, SwitchPosition.MIDDLE.auxFunctionLevel());
        assertEquals(2, SwitchPosition.HIGH.auxFunctionLevel());
    }

    @Test
    void theDetentsSitOutsideArdupilotsOwnDeadBand() {
        // A platform detent must be unambiguous to the firmware too, or the station and the vehicle
        // disagree about what the operator just did (CONTROLLER-SETUP-CONTEXT.md C4).
        assertEquals(RcChannels.MIN_MICROS < SwitchPosition.LOW_MICROS, true);
        assertEquals(RcChannels.MAX_MICROS > SwitchPosition.HIGH_MICROS, true);
        assertEquals(true, ControlBinding.CENTER_MICROS > SwitchPosition.LOW_MICROS
                && ControlBinding.CENTER_MICROS < SwitchPosition.HIGH_MICROS);
    }

    @Test
    void rejectsNullArguments() {
        assertThrows(IllegalArgumentException.class,
                () -> SwitchPosition.of(null, ControlInputKind.SWITCH_2, 0.0));
        assertThrows(IllegalArgumentException.class,
                () -> SwitchPosition.of(ControlBinding.Source.AXIS, null, 0.0));
    }
}
