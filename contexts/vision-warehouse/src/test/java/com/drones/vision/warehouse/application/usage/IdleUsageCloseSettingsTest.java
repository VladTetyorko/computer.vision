package com.drones.vision.warehouse.application.usage;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class IdleUsageCloseSettingsTest {

    @Test
    void rejectsAMissingZeroOrNegativeIdleThreshold() {
        assertThrows(IllegalArgumentException.class, () -> new IdleUsageCloseSettings(null));
        assertThrows(IllegalArgumentException.class, () -> new IdleUsageCloseSettings(Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new IdleUsageCloseSettings(Duration.ofMinutes(-1)));
    }

    @Test
    void defaultsToTenMinutes() {
        assertEquals(Duration.ofMinutes(10), IdleUsageCloseSettings.defaults().idleThreshold());
    }
}
