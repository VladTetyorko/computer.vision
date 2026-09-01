package com.drones.vision.flight.application.alerting;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BatteryAlertSettingsTest {

    @Test
    void defaultsAreTheD6FrozenValues() {
        BatteryAlertSettings settings = BatteryAlertSettings.defaults();

        assertEquals(10.0, settings.criticalPercent());
        assertEquals(25.0, settings.warningPercent());
    }

    @Test
    void rejectsWarningAtOrBelowCritical() {
        assertThrows(IllegalArgumentException.class, () -> new BatteryAlertSettings(25.0, 25.0));
        assertThrows(IllegalArgumentException.class, () -> new BatteryAlertSettings(30.0, 25.0));
    }

    @Test
    void rejectsOutOfRangePercentages() {
        assertThrows(IllegalArgumentException.class, () -> new BatteryAlertSettings(-1.0, 25.0));
        assertThrows(IllegalArgumentException.class, () -> new BatteryAlertSettings(10.0, 101.0));
        assertThrows(IllegalArgumentException.class, () -> new BatteryAlertSettings(Double.NaN, 25.0));
    }

    @Test
    void acceptsAValidPair() {
        BatteryAlertSettings settings = new BatteryAlertSettings(5.0, 20.0);

        assertEquals(5.0, settings.criticalPercent());
        assertEquals(20.0, settings.warningPercent());
    }
}
