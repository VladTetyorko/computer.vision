package com.drones.vision.flight.domain.model;

import com.drones.vision.kernel.DeviceId;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TelemetryTest {

    @Test
    void extraIsDefensivelyCopied() {
        Map<String, Double> extra = new HashMap<>();
        extra.put("rssi", -60.0);

        Telemetry telemetry = new Telemetry(DeviceId.random(), Instant.now(), null, null, null, null, null, extra);

        extra.put("temp", 42.0);

        assertEquals(1, telemetry.extra().size(), "later mutation of the source map must not affect the telemetry");
        assertThrows(UnsupportedOperationException.class, () -> telemetry.extra().put("x", 1.0),
                "returned extra map must be immutable");
    }

    @Test
    void nullableValueFieldsAreAllowed() {
        Telemetry telemetry = new Telemetry(DeviceId.random(), Instant.now(), null, null, null, null, null, Map.of());

        assertNull(telemetry.latitude());
        assertNull(telemetry.longitude());
        assertNull(telemetry.altitudeMeters());
        assertNull(telemetry.headingDegrees());
        assertNull(telemetry.batteryPercent());
    }

    @Test
    void rejectsNullRequiredFields() {
        Instant now = Instant.now();
        DeviceId id = DeviceId.random();

        assertThrows(IllegalArgumentException.class,
                () -> new Telemetry(null, now, null, null, null, null, null, Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new Telemetry(id, null, null, null, null, null, null, Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new Telemetry(id, now, null, null, null, null, null, null));
    }

    @Test
    void eightArgConstructorDefaultsFlightStateToNull() {
        Telemetry telemetry = new Telemetry(DeviceId.random(), Instant.now(), null, null, null, null, null, Map.of());

        assertNull(telemetry.flightState());
    }

    @Test
    void nineArgConstructorAcceptsAnExplicitFlightState() {
        FlightState flightState = new FlightState("ardupilot", "RTL", true, true, 3, 12, 0.9, 87, java.util.List.of());

        Telemetry telemetry = new Telemetry(DeviceId.random(), Instant.now(), null, null, null, null, null, Map.of(),
                flightState);

        assertEquals(flightState, telemetry.flightState());
    }
}
