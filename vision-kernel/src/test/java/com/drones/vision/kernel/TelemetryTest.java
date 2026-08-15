package com.drones.vision.kernel;

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

    @Test
    void nineArgConstructorDefaultsAglAttitudeAndDeviceBootMillisToNull() {
        Telemetry telemetry = new Telemetry(DeviceId.random(), Instant.now(), null, null, null, null, null, Map.of(),
                FlightState.empty());

        assertNull(telemetry.aglMeters());
        assertNull(telemetry.attitude());
        assertNull(telemetry.deviceBootMillis());
    }

    @Test
    void eightArgConstructorDefaultsAglAttitudeAndDeviceBootMillisToNullToo() {
        Telemetry telemetry = new Telemetry(DeviceId.random(), Instant.now(), null, null, null, null, null, Map.of());

        assertNull(telemetry.aglMeters());
        assertNull(telemetry.attitude());
        assertNull(telemetry.deviceBootMillis());
    }

    @Test
    void twelveArgConstructorAcceptsAglAttitudeAndDeviceBootMillis() {
        Attitude attitude = new Attitude(1.0, 2.0, 3.0, null, -30.0, 90.0);

        Telemetry telemetry = new Telemetry(DeviceId.random(), Instant.now(), null, null, 180.0, null, null, Map.of(),
                null, 42.5, attitude, 12_345L);

        assertEquals(42.5, telemetry.aglMeters());
        assertEquals(attitude, telemetry.attitude());
        assertEquals(12_345L, telemetry.deviceBootMillis());
        assertEquals(180.0, telemetry.altitudeMeters(), "altitudeMeters stays AMSL, independent of aglMeters");
    }

    @Test
    void rejectsNonFiniteAglMeters() {
        Instant now = Instant.now();
        DeviceId id = DeviceId.random();

        assertThrows(IllegalArgumentException.class,
                () -> new Telemetry(id, now, null, null, null, null, null, Map.of(), null, Double.NaN, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> new Telemetry(id, now, null, null, null, null, null, Map.of(), null,
                        Double.POSITIVE_INFINITY, null, null));
    }

    @Test
    void rejectsNegativeDeviceBootMillis() {
        Instant now = Instant.now();
        DeviceId id = DeviceId.random();

        assertThrows(IllegalArgumentException.class,
                () -> new Telemetry(id, now, null, null, null, null, null, Map.of(), null, null, null, -1L));
    }
}
