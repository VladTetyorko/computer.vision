package com.drones.vision.domain.model;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlightStateTest {

    @Test
    void emptyHasEveryFieldNullAndNoArmingBlockers() {
        FlightState state = FlightState.empty();

        assertNull(state.firmware());
        assertNull(state.mode());
        assertNull(state.armed());
        assertNull(state.failsafe());
        assertNull(state.gpsFixType());
        assertNull(state.satellites());
        assertNull(state.hdop());
        assertNull(state.rssiPercent());
        assertTrue(state.armingBlockers().isEmpty());
    }

    @Test
    void allFieldsExceptArmingBlockersAreNullable() {
        FlightState state = new FlightState(null, null, null, null, null, null, null, null, List.of());

        assertNull(state.firmware());
        assertNull(state.mode());
        assertNull(state.armed());
        assertNull(state.failsafe());
    }

    @Test
    void acceptsAFullyPopulatedState() {
        FlightState state = new FlightState("ardupilot", "RTL", true, true, 3, 12, 0.9, 87, List.of("Compass"));

        assertEquals("ardupilot", state.firmware());
        assertEquals("RTL", state.mode());
        assertEquals(true, state.armed());
        assertEquals(true, state.failsafe());
        assertEquals(3, state.gpsFixType());
        assertEquals(12, state.satellites());
        assertEquals(0.9, state.hdop());
        assertEquals(87, state.rssiPercent());
        assertEquals(List.of("Compass"), state.armingBlockers());
    }

    @Test
    void armingBlockersAreDefensivelyCopied() {
        List<String> blockers = new ArrayList<>();
        blockers.add("PreArm: Compass not calibrated");

        FlightState state = new FlightState(null, null, null, null, null, null, null, null, blockers);

        blockers.add("PreArm: GPS not healthy");

        assertEquals(1, state.armingBlockers().size(), "later mutation of the source list must not affect the state");
        assertThrows(UnsupportedOperationException.class, () -> state.armingBlockers().add("x"),
                "returned arming blockers list must be immutable");
    }

    @Test
    void rejectsNullArmingBlockers() {
        assertThrows(IllegalArgumentException.class,
                () -> new FlightState(null, null, null, null, null, null, null, null, null));
    }

    @Test
    void rejectsGpsFixTypeOutsideZeroToEight() {
        assertThrows(IllegalArgumentException.class,
                () -> new FlightState(null, null, null, null, -1, null, null, null, List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new FlightState(null, null, null, null, 9, null, null, null, List.of()));
    }

    @Test
    void acceptsGpsFixTypeBoundaries() {
        assertEquals(0, new FlightState(null, null, null, null, 0, null, null, null, List.of()).gpsFixType());
        assertEquals(8, new FlightState(null, null, null, null, 8, null, null, null, List.of()).gpsFixType());
    }

    @Test
    void rejectsNegativeSatellites() {
        assertThrows(IllegalArgumentException.class,
                () -> new FlightState(null, null, null, null, null, -1, null, null, List.of()));
    }

    @Test
    void rejectsNegativeHdop() {
        assertThrows(IllegalArgumentException.class,
                () -> new FlightState(null, null, null, null, null, null, -0.01, null, List.of()));
    }

    @Test
    void rejectsRssiPercentOutsideZeroToOneHundred() {
        assertThrows(IllegalArgumentException.class,
                () -> new FlightState(null, null, null, null, null, null, null, -1, List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new FlightState(null, null, null, null, null, null, null, 101, List.of()));
    }

    @Test
    void acceptsRssiPercentBoundaries() {
        assertEquals(0, new FlightState(null, null, null, null, null, null, null, 0, List.of()).rssiPercent());
        assertEquals(100, new FlightState(null, null, null, null, null, null, null, 100, List.of()).rssiPercent());
    }

    @Test
    void twoEmptyCallsAreEqualValues() {
        assertEquals(FlightState.empty(), FlightState.empty());
    }
}
