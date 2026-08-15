package com.drones.vision.adapter.mavlink;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class MavlinkRouteTest {

    @Test
    void parseReturnsNullForAbsentOrBlankRoute() {
        assertNull(MavlinkRoute.parse(null));
        assertNull(MavlinkRoute.parse(""));
        assertNull(MavlinkRoute.parse("   "));
    }

    @Test
    void parseReturnsNullForFewerThanTwoPoints() {
        assertNull(MavlinkRoute.parse("50.0,30.0"));
    }

    @Test
    void parseReturnsNullForMalformedNumbers() {
        assertNull(MavlinkRoute.parse("50.0,thirty;51.0,31.0"));
    }

    @Test
    void parseReturnsNullForAWronglyShapedPoint() {
        assertNull(MavlinkRoute.parse("50.0,30.0,100.0,extra;51.0,31.0"));
    }

    @Test
    void positionAtStartOfRouteMatchesTheFirstWaypoint() {
        MavlinkRoute route = MavlinkRoute.parse("50.0,30.0;50.01,30.0");
        assertNotNull(route);

        MavlinkRoute.Position start = route.positionAt(0.0);
        assertEquals(50.0, start.latitude(), 1e-9);
        assertEquals(30.0, start.longitude(), 1e-9);
    }

    @Test
    void positionAtHalfTheFirstLegIsHalfwayBetweenItsWaypoints() {
        MavlinkRoute route = MavlinkRoute.parse("50.0,30.0;50.01,30.0");
        assertNotNull(route);
        double legLengthMeters = distanceMeters(50.0, 30.0, 50.01, 30.0);

        MavlinkRoute.Position midpoint = route.positionAt(legLengthMeters / 2.0);

        assertEquals(50.005, midpoint.latitude(), 1e-4);
        assertEquals(30.0, midpoint.longitude(), 1e-9);
        assertEquals(0.0, midpoint.headingDegrees(), 1e-6, "heading north along a pure-latitude leg");
    }

    @Test
    void loopsBackToTheStartPastTheClosingLeg() {
        // A 2-point route's "loop" is just the leg out and the leg back -- one full lap should
        // land back at the start.
        MavlinkRoute route = MavlinkRoute.parse("50.0,30.0;50.01,30.0");
        assertNotNull(route);
        double lapLengthMeters = 2 * distanceMeters(50.0, 30.0, 50.01, 30.0);

        MavlinkRoute.Position afterOneLap = route.positionAt(lapLengthMeters);

        assertEquals(50.0, afterOneLap.latitude(), 1e-6);
        assertEquals(30.0, afterOneLap.longitude(), 1e-6);
    }

    @Test
    void negativeDistanceIsClampedToTheStart() {
        MavlinkRoute route = MavlinkRoute.parse("50.0,30.0;50.01,30.0");
        assertNotNull(route);

        MavlinkRoute.Position position = route.positionAt(-500.0);

        assertEquals(50.0, position.latitude(), 1e-9);
        assertEquals(30.0, position.longitude(), 1e-9);
    }

    @Test
    void altitudeInterpolatesLinearlyWhenBothEndpointsGiveIt() {
        MavlinkRoute route = MavlinkRoute.parse("50.0,30.0,100;50.01,30.0,200");
        assertNotNull(route);
        double legLengthMeters = distanceMeters(50.0, 30.0, 50.01, 30.0);

        MavlinkRoute.Position midpoint = route.positionAt(legLengthMeters / 2.0);

        assertNotNull(midpoint.altitudeMeters());
        assertEquals(150.0, midpoint.altitudeMeters(), 1.0);
    }

    @Test
    void altitudeIsNullWhenEitherEndpointOmitsIt() {
        MavlinkRoute route = MavlinkRoute.parse("50.0,30.0,100;50.01,30.0");
        assertNotNull(route);

        MavlinkRoute.Position start = route.positionAt(0.0);

        assertNull(start.altitudeMeters());
    }

    /** Same equirectangular approximation {@link MavlinkRoute} itself uses, for test expectations. */
    private static double distanceMeters(double lat1, double lon1, double lat2, double lon2) {
        double earthRadiusMeters = 6_371_000.0;
        double meanLatitudeRadians = Math.toRadians((lat1 + lat2) / 2.0);
        double northMeters = Math.toRadians(lat2 - lat1) * earthRadiusMeters;
        double eastMeters = Math.toRadians(lon2 - lon1) * earthRadiusMeters * Math.cos(meanLatitudeRadians);
        return Math.sqrt(northMeters * northMeters + eastMeters * eastMeters);
    }
}
