package com.drones.vision.domain.model;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlightCapabilityTest {

    @Test
    void notCommandableIsAllFalseWithNoModes() {
        FlightCapability caps = FlightCapability.notCommandable();

        assertFalse(caps.commandable());
        assertFalse(caps.armSupported());
        assertFalse(caps.modeSelectSupported());
        assertTrue(caps.selectableModes().isEmpty());
    }

    @Test
    void keepsAllFieldsAndModes() {
        FlightCapability caps = new FlightCapability(true, true, true, List.of("Loiter", "RTL"));

        assertTrue(caps.commandable());
        assertTrue(caps.armSupported());
        assertTrue(caps.modeSelectSupported());
        assertEquals(List.of("Loiter", "RTL"), caps.selectableModes());
    }

    @Test
    void defensivelyCopiesSelectableModes() {
        List<String> modes = new ArrayList<>(List.of("Loiter", "RTL"));
        FlightCapability caps = new FlightCapability(true, true, true, modes);

        modes.add("Guided");

        assertEquals(List.of("Loiter", "RTL"), caps.selectableModes());
        assertThrows(UnsupportedOperationException.class, () -> caps.selectableModes().add("Auto"));
    }

    @Test
    void rejectsNullSelectableModes() {
        assertThrows(NullPointerException.class, () -> new FlightCapability(false, false, false, null));
    }
}
