package com.drones.mavlink;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompIdTest {

    @Test
    void acceptsTheFullValidRange() {
        assertEquals(1, new CompId(1).value());
        assertEquals(255, new CompId(255).value());
    }

    @Test
    void rejectsZero() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> new CompId(0));
        assertTrue(e.getMessage().contains("0"), "message should name the bad value: " + e.getMessage());
    }

    @Test
    void rejectsAboveTwoFiftyFive() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> new CompId(300));
        assertTrue(e.getMessage().contains("300"), "message should name the bad value: " + e.getMessage());
    }
}
