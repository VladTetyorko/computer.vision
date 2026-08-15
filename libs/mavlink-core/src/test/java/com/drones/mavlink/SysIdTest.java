package com.drones.mavlink;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SysIdTest {

    @Test
    void acceptsTheFullValidRange() {
        assertEquals(1, new SysId(1).value());
        assertEquals(255, new SysId(255).value());
        assertEquals(128, new SysId(128).value());
    }

    @Test
    void rejectsZero() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> new SysId(0));
        assertTrue(e.getMessage().contains("0"), "message should name the bad value: " + e.getMessage());
    }

    @Test
    void rejectsBelowZero() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> new SysId(-1));
        assertTrue(e.getMessage().contains("-1"), "message should name the bad value: " + e.getMessage());
    }

    @Test
    void rejectsAboveTwoFiftyFive() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> new SysId(256));
        assertTrue(e.getMessage().contains("256"), "message should name the bad value: " + e.getMessage());
    }
}
