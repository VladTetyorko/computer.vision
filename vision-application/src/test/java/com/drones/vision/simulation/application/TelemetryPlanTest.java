package com.drones.vision.simulation.application;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TelemetryPlanTest {

    private static final Waypoint A = new Waypoint(50.45, 30.52, null);
    private static final Waypoint B = new Waypoint(50.46, 30.53, null);

    @Test
    void allowsANullSpeedModeAndRoute() {
        TelemetryPlan plan = new TelemetryPlan(null, null, null);
        assertNull(plan.speedMps());
        assertNull(plan.mode());
        assertNull(plan.route());
    }

    @Test
    void rejectsANonPositiveSpeed() {
        assertThrows(IllegalArgumentException.class, () -> new TelemetryPlan(0.0, null, List.of(A, B)));
        assertThrows(IllegalArgumentException.class, () -> new TelemetryPlan(-5.0, null, List.of(A, B)));
    }

    @Test
    void acceptsAPositiveSpeed() {
        assertEquals(12.0, new TelemetryPlan(12.0, null, List.of(A, B)).speedMps());
    }

    @Test
    void rejectsARouteWithFewerThanTwoWaypoints() {
        assertThrows(IllegalArgumentException.class, () -> new TelemetryPlan(null, null, List.of(A)));
        assertThrows(IllegalArgumentException.class, () -> new TelemetryPlan(null, null, List.of()));
    }

    @Test
    void acceptsARouteWithAtLeastTwoWaypoints() {
        TelemetryPlan plan = new TelemetryPlan(null, RouteMode.ONCE, List.of(A, B));
        assertEquals(List.of(A, B), plan.route());
        assertEquals(RouteMode.ONCE, plan.mode());
    }

    @Test
    void copiesTheRouteSoLaterMutationOfTheCallersListCannotLeakIn() {
        List<Waypoint> mutable = new ArrayList<>(List.of(A, B));
        TelemetryPlan plan = new TelemetryPlan(null, null, mutable);

        mutable.add(new Waypoint(0.0, 0.0, null));

        assertEquals(List.of(A, B), plan.route());
    }
}
