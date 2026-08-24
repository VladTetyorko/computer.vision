package com.drones.vision.flight.domain.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ControlBindingTest {

    private static ControlBinding axis(double deadband, boolean reversed) {
        return new ControlBinding(ControlBinding.Source.AXIS, ControlInputKind.AXIS, ControlFunction.ROLL, 0, 1, 1000, 1500, 2000, deadband, reversed);
    }

    private static ControlBinding button(double deadband, boolean reversed) {
        return new ControlBinding(ControlBinding.Source.BUTTON, ControlInputKind.BUTTON, ControlFunction.AUX_1, 0, 5, 1000, 1000, 2000, deadband, reversed);
    }

    // --- compact ctor validation ---

    @Test
    void rejectsNullSource() {
        assertThrows(IllegalArgumentException.class,
                () -> new ControlBinding(null, ControlInputKind.AXIS, ControlFunction.ROLL, 0, 1, 1000, 1500, 2000, 0.0, false));
    }

    @Test
    void rejectsNegativeSourceIndex() {
        assertThrows(IllegalArgumentException.class,
                () -> new ControlBinding(ControlBinding.Source.AXIS, ControlInputKind.AXIS, ControlFunction.ROLL, -1, 1, 1000, 1500, 2000, 0.0, false));
    }

    @Test
    void rejectsRcChannelOutOfRange() {
        assertThrows(IllegalArgumentException.class,
                () -> new ControlBinding(ControlBinding.Source.AXIS, ControlInputKind.AXIS, ControlFunction.ROLL, 0, 0, 1000, 1500, 2000, 0.0, false));
        assertThrows(IllegalArgumentException.class,
                () -> new ControlBinding(ControlBinding.Source.AXIS, ControlInputKind.AXIS, ControlFunction.ROLL, 0, 19, 1000, 1500, 2000, 0.0, false));
    }

    @Test
    void rejectsMicrosOutsideDeviceRange() {
        assertThrows(IllegalArgumentException.class,
                () -> new ControlBinding(ControlBinding.Source.AXIS, ControlInputKind.AXIS, ControlFunction.ROLL, 0, 1, 999, 1500, 2000, 0.0, false));
        assertThrows(IllegalArgumentException.class,
                () -> new ControlBinding(ControlBinding.Source.AXIS, ControlInputKind.AXIS, ControlFunction.ROLL, 0, 1, 1000, 1500, 2001, 0.0, false));
        assertThrows(IllegalArgumentException.class,
                () -> new ControlBinding(ControlBinding.Source.AXIS, ControlInputKind.AXIS, ControlFunction.ROLL, 0, 1, 1000, 2001, 2000, 0.0, false));
    }

    @Test
    void rejectsOutOfOrderMicros() {
        assertThrows(IllegalArgumentException.class,
                () -> new ControlBinding(ControlBinding.Source.AXIS, ControlInputKind.AXIS, ControlFunction.ROLL, 0, 1, 1600, 1500, 2000, 0.0, false));
        assertThrows(IllegalArgumentException.class,
                () -> new ControlBinding(ControlBinding.Source.AXIS, ControlInputKind.AXIS, ControlFunction.ROLL, 0, 1, 1000, 1500, 1400, 0.0, false));
    }

    @Test
    void acceptsMinEqualsCenterEqualsMax() {
        ControlBinding binding = new ControlBinding(ControlBinding.Source.BUTTON, ControlInputKind.BUTTON, ControlFunction.AUX_1, 0, 5, 1500, 1500, 1500, 0.0, false);

        assertEquals(1500, binding.toMicros(0.0));
        assertEquals(1500, binding.toMicros(1.0));
    }

    @Test
    void rejectsDeadbandOutsideZeroToOne() {
        assertThrows(IllegalArgumentException.class,
                () -> new ControlBinding(ControlBinding.Source.AXIS, ControlInputKind.AXIS, ControlFunction.ROLL, 0, 1, 1000, 1500, 2000, -0.01, false));
        assertThrows(IllegalArgumentException.class,
                () -> new ControlBinding(ControlBinding.Source.AXIS, ControlInputKind.AXIS, ControlFunction.ROLL, 0, 1, 1000, 1500, 2000, 1.01, false));
        assertThrows(IllegalArgumentException.class,
                () -> new ControlBinding(ControlBinding.Source.AXIS, ControlInputKind.AXIS, ControlFunction.ROLL, 0, 1, 1000, 1500, 2000, Double.NaN, false));
    }

    // --- toMicros: AXIS ---

    @Test
    void axisCenterMapsToCenterMicros() {
        assertEquals(1500, axis(0.0, false).toMicros(0.0));
    }

    @Test
    void axisEndpointsMapToMinAndMax() {
        ControlBinding binding = axis(0.0, false);

        assertEquals(1000, binding.toMicros(-1.0));
        assertEquals(2000, binding.toMicros(1.0));
    }

    @Test
    void axisMidpointsAreLinear() {
        ControlBinding binding = axis(0.0, false);

        assertEquals(1750, binding.toMicros(0.5));
        assertEquals(1250, binding.toMicros(-0.5));
    }

    @Test
    void axisReversedFlipsDirection() {
        ControlBinding binding = axis(0.0, true);

        assertEquals(2000, binding.toMicros(-1.0));
        assertEquals(1000, binding.toMicros(1.0));
        assertEquals(1500, binding.toMicros(0.0));
    }

    @Test
    void axisDeadbandSnapsWithinBoundToCenterAndIsInclusive() {
        ControlBinding binding = axis(0.1, false);

        assertEquals(1500, binding.toMicros(0.05));
        assertEquals(1500, binding.toMicros(-0.05));
        assertEquals(1500, binding.toMicros(0.1));   // exactly on the boundary: inclusive
        assertEquals(1500, binding.toMicros(-0.1));
    }

    @Test
    void axisJustOutsideDeadbandIsNotSnapped() {
        ControlBinding binding = axis(0.1, false);

        int justAbove = binding.toMicros(0.1 + 1e-9);
        int justBelow = binding.toMicros(-(0.1 + 1e-9));

        assertEquals(1550, justAbove);
        assertEquals(1450, justBelow);
    }

    @Test
    void axisClampsOutOfRangeInput() {
        ControlBinding binding = axis(0.0, false);

        assertEquals(2000, binding.toMicros(5.0));
        assertEquals(1000, binding.toMicros(-5.0));
    }

    // --- toMicros: BUTTON ---

    @Test
    void buttonRestMapsToMinMicros() {
        assertEquals(1000, button(0.0, false).toMicros(0.0));
    }

    @Test
    void buttonPressedMapsToMaxMicros() {
        assertEquals(2000, button(0.0, false).toMicros(1.0));
    }

    @Test
    void buttonMidpointIsLinear() {
        assertEquals(1500, button(0.0, false).toMicros(0.5));
    }

    @Test
    void buttonReversedFlipsPressedAndUnpressed() {
        ControlBinding binding = button(0.0, true);

        assertEquals(2000, binding.toMicros(0.0));
        assertEquals(1000, binding.toMicros(1.0));
    }

    @Test
    void buttonDeadbandSnapsNearRestToMinMicros() {
        ControlBinding binding = button(0.1, false);

        assertEquals(1000, binding.toMicros(0.05));
        assertEquals(1000, binding.toMicros(0.1)); // inclusive boundary
    }

    @Test
    void buttonJustOutsideDeadbandIsNotSnapped() {
        ControlBinding binding = button(0.1, false);

        assertEquals(1100, binding.toMicros(0.1 + 1e-9));
    }

    @Test
    void buttonClampsOutOfRangeInput() {
        ControlBinding binding = button(0.0, false);

        assertEquals(2000, binding.toMicros(5.0));
        assertEquals(1000, binding.toMicros(-5.0));
    }
}
