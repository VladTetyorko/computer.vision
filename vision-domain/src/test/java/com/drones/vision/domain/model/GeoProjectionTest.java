package com.drones.vision.domain.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeoProjectionTest {

    private static final double DELTA = 1e-6;
    // Loose enough for great-circle vs. simple-trig sanity checks over small ranges (meters).
    private static final double METERS_DELTA = 0.5;

    // --- project(): nadir ------------------------------------------------------------

    @Test
    void nadirDepressionReturnsDronesOwnGroundPoint() {
        GeoPosition drone = new GeoPosition(50.0, 30.0, 120.0);

        GeoPosition ground = GeoProjection.project(drone, 45.0, 120.0, 90.0);

        assertEquals(drone.latitude(), ground.latitude(), DELTA);
        assertEquals(drone.longitude(), ground.longitude(), DELTA);
        assertNull(ground.altitudeMeters(), "ground point altitude is unknown");
    }

    @Test
    void nadirIsIndependentOfHeading() {
        GeoPosition drone = new GeoPosition(-10.0, 100.0, 50.0);

        GeoPosition north = GeoProjection.project(drone, 0.0, 50.0, 90.0);
        GeoPosition east = GeoProjection.project(drone, 90.0, 50.0, 90.0);
        GeoPosition south = GeoProjection.project(drone, 180.0, 50.0, 90.0);

        assertEquals(north.latitude(), east.latitude(), DELTA);
        assertEquals(north.longitude(), east.longitude(), DELTA);
        assertEquals(north.latitude(), south.latitude(), DELTA);
        assertEquals(north.longitude(), south.longitude(), DELTA);
    }

    @Test
    void zeroAltitudeReturnsDronesOwnGroundPointRegardlessOfDepression() {
        GeoPosition drone = new GeoPosition(50.0, 30.0, 0.0);

        GeoPosition ground = GeoProjection.project(drone, 45.0, 0.0, 30.0);

        assertEquals(drone.latitude(), ground.latitude(), DELTA);
        assertEquals(drone.longitude(), ground.longitude(), DELTA);
    }

    // --- project(): cardinal headings at 45 degrees depression --------------------------

    @Test
    void headingZeroProjectsDueNorthAtSlantRangeEqualToAltitude() {
        GeoPosition drone = new GeoPosition(50.0, 30.0, 100.0);

        GeoPosition ground = GeoProjection.project(drone, 0.0, 100.0, GeoProjection.DEFAULT_DEPRESSION_DEGREES);

        assertTrue(ground.latitude() > drone.latitude(), "north increases latitude");
        assertEquals(drone.longitude(), ground.longitude(), DELTA, "due north keeps longitude unchanged");
        BearingDistance bd = GeoProjection.bearingDistance(drone, ground);
        assertEquals(0.0, bd.bearingDegrees(), DELTA);
        assertEquals(100.0, bd.distanceMeters(), METERS_DELTA, "45 deg depression: ground range == altitude");
    }

    @Test
    void headingNinetyProjectsDueEastAtSlantRangeEqualToAltitude() {
        GeoPosition drone = new GeoPosition(0.0, 30.0, 100.0);

        GeoPosition ground = GeoProjection.project(drone, 90.0, 100.0, GeoProjection.DEFAULT_DEPRESSION_DEGREES);

        assertTrue(ground.longitude() > drone.longitude(), "east increases longitude");
        assertEquals(drone.latitude(), ground.latitude(), DELTA, "due east keeps latitude unchanged at the equator");
        BearingDistance bd = GeoProjection.bearingDistance(drone, ground);
        assertEquals(90.0, bd.bearingDegrees(), DELTA);
        assertEquals(100.0, bd.distanceMeters(), METERS_DELTA);
    }

    @Test
    void headingOneEightyProjectsDueSouth() {
        GeoPosition drone = new GeoPosition(50.0, 30.0, 100.0);

        GeoPosition ground = GeoProjection.project(drone, 180.0, 100.0, GeoProjection.DEFAULT_DEPRESSION_DEGREES);

        assertTrue(ground.latitude() < drone.latitude(), "south decreases latitude");
        assertEquals(drone.longitude(), ground.longitude(), DELTA);
        BearingDistance bd = GeoProjection.bearingDistance(drone, ground);
        assertEquals(180.0, bd.bearingDegrees(), DELTA);
    }

    @Test
    void headingTwoSeventyProjectsDueWest() {
        GeoPosition drone = new GeoPosition(0.0, 30.0, 100.0);

        GeoPosition ground = GeoProjection.project(drone, 270.0, 100.0, GeoProjection.DEFAULT_DEPRESSION_DEGREES);

        assertTrue(ground.longitude() < drone.longitude(), "west decreases longitude");
        BearingDistance bd = GeoProjection.bearingDistance(drone, ground);
        assertEquals(270.0, bd.bearingDegrees(), DELTA);
    }

    @Test
    void headingWrapsThreeSixtyToZero() {
        GeoPosition drone = new GeoPosition(50.0, 30.0, 100.0);

        GeoPosition zero = GeoProjection.project(drone, 0.0, 100.0, 45.0);
        GeoPosition wrapped = GeoProjection.project(drone, 360.0, 100.0, 45.0);

        assertEquals(zero.latitude(), wrapped.latitude(), DELTA);
        assertEquals(zero.longitude(), wrapped.longitude(), DELTA);
    }

    @Test
    void headingWrapsNegativeAndOverflowValuesEquivalently() {
        GeoPosition drone = new GeoPosition(50.0, 30.0, 100.0);

        GeoPosition ninety = GeoProjection.project(drone, 90.0, 100.0, 45.0);
        GeoPosition negativeWrap = GeoProjection.project(drone, -270.0, 100.0, 45.0);
        GeoPosition overflowWrap = GeoProjection.project(drone, 450.0, 100.0, 45.0);

        assertEquals(ninety.latitude(), negativeWrap.latitude(), DELTA);
        assertEquals(ninety.longitude(), negativeWrap.longitude(), DELTA);
        assertEquals(ninety.latitude(), overflowWrap.latitude(), DELTA);
        assertEquals(ninety.longitude(), overflowWrap.longitude(), DELTA);
    }

    // --- project(): ground range vs. depression ------------------------------------------

    @Test
    void groundRangeMatchesAltitudeOverTangentOfDepression() {
        GeoPosition drone = new GeoPosition(50.0, 30.0, 200.0);
        double depression = 30.0;
        double expectedRange = 200.0 / Math.tan(Math.toRadians(depression));

        GeoPosition ground = GeoProjection.project(drone, 0.0, 200.0, depression);

        double actualRange = GeoProjection.bearingDistance(drone, ground).distanceMeters();
        assertEquals(expectedRange, actualRange, METERS_DELTA);
    }

    @Test
    void steeperDepressionYieldsShorterGroundRange() {
        GeoPosition drone = new GeoPosition(50.0, 30.0, 200.0);

        GeoPosition shallow = GeoProjection.project(drone, 0.0, 200.0, 20.0);
        GeoPosition steep = GeoProjection.project(drone, 0.0, 200.0, 70.0);

        double shallowRange = GeoProjection.bearingDistance(drone, shallow).distanceMeters();
        double steepRange = GeoProjection.bearingDistance(drone, steep).distanceMeters();
        assertTrue(steepRange < shallowRange);
    }

    // --- project(): golden value -----------------------------------------------------------

    @Test
    void goldenValueDueNorthFortyFiveDegreeDepression() {
        // At 45 deg depression, ground range == altitude. Due north over a small range, the
        // spherical destination-point formula reduces to a simple angular-distance addition along
        // the meridian: lat2 = lat1 + range/R (radians).
        GeoPosition drone = new GeoPosition(10.0, 20.0, 100.0);
        double expectedLat = 10.0 + Math.toDegrees(100.0 / GeoProjection.EARTH_RADIUS_METERS);

        GeoPosition ground = GeoProjection.project(drone, 0.0, 100.0, 45.0);

        assertEquals(expectedLat, ground.latitude(), 1e-9);
        assertEquals(20.0, ground.longitude(), 1e-9);
    }

    // --- project(): antimeridian / high latitude sanity -----------------------------------

    @Test
    void projectingEastAcrossTheAntimeridianWrapsLongitudeIntoValidRange() {
        GeoPosition drone = new GeoPosition(0.0, 179.9999, 100.0);

        GeoPosition ground = GeoProjection.project(drone, 90.0, 500.0, 10.0);

        assertTrue(ground.longitude() >= -180.0 && ground.longitude() <= 180.0);
        assertTrue(ground.longitude() < 0.0, "projecting east across +180 must wrap to a negative longitude");
    }

    @Test
    void projectingNearThePoleDoesNotProduceNaN() {
        GeoPosition drone = new GeoPosition(89.9999, 0.0, 500.0);

        GeoPosition ground = GeoProjection.project(drone, 45.0, 500.0, 20.0);

        assertFalse(Double.isNaN(ground.latitude()));
        assertFalse(Double.isNaN(ground.longitude()));
        assertTrue(ground.latitude() >= -90.0 && ground.latitude() <= 90.0);
        assertTrue(ground.longitude() >= -180.0 && ground.longitude() <= 180.0);
    }

    @Test
    void veryShallowDepressionProducesLargeRangeWithoutNaN() {
        GeoPosition drone = new GeoPosition(0.0, 0.0, 100.0);

        GeoPosition ground = GeoProjection.project(drone, 0.0, 100.0, 0.01);

        assertFalse(Double.isNaN(ground.latitude()));
        assertFalse(Double.isNaN(ground.longitude()));
    }

    // --- project(): validation ---------------------------------------------------------------

    @Test
    void rejectsNullDrone() {
        assertThrows(IllegalArgumentException.class, () -> GeoProjection.project(null, 0.0, 100.0, 45.0));
    }

    @Test
    void rejectsNegativeAltitude() {
        GeoPosition drone = new GeoPosition(0.0, 0.0, 0.0);

        assertThrows(IllegalArgumentException.class, () -> GeoProjection.project(drone, 0.0, -1.0, 45.0));
    }

    @Test
    void rejectsNonPositiveDepression() {
        GeoPosition drone = new GeoPosition(0.0, 0.0, 0.0);

        assertThrows(IllegalArgumentException.class, () -> GeoProjection.project(drone, 0.0, 100.0, 0.0));
        assertThrows(IllegalArgumentException.class, () -> GeoProjection.project(drone, 0.0, 100.0, -10.0));
    }

    @Test
    void rejectsDepressionAboveNinety() {
        GeoPosition drone = new GeoPosition(0.0, 0.0, 0.0);

        assertThrows(IllegalArgumentException.class, () -> GeoProjection.project(drone, 0.0, 100.0, 90.1));
    }

    @Test
    void acceptsDepressionExactlyNinety() {
        GeoPosition drone = new GeoPosition(0.0, 0.0, 0.0);

        GeoPosition ground = GeoProjection.project(drone, 0.0, 100.0, 90.0);

        assertEquals(drone.latitude(), ground.latitude(), DELTA);
    }

    @Test
    void rejectsNonFiniteHeading() {
        GeoPosition drone = new GeoPosition(0.0, 0.0, 0.0);

        assertThrows(IllegalArgumentException.class,
                () -> GeoProjection.project(drone, Double.NaN, 100.0, 45.0));
        assertThrows(IllegalArgumentException.class,
                () -> GeoProjection.project(drone, Double.POSITIVE_INFINITY, 100.0, 45.0));
    }

    // --- bearingDistance(): zero distance --------------------------------------------------

    @Test
    void bearingDistanceIsZeroForIdenticalPoints() {
        GeoPosition p = new GeoPosition(12.0, 34.0, null);

        BearingDistance bd = GeoProjection.bearingDistance(p, p);

        assertEquals(0.0, bd.distanceMeters(), DELTA);
    }

    // --- bearingDistance(): cardinal directions --------------------------------------------

    @Test
    void bearingIsZeroDueNorth() {
        GeoPosition from = new GeoPosition(0.0, 0.0, null);
        GeoPosition to = new GeoPosition(10.0, 0.0, null);

        BearingDistance bd = GeoProjection.bearingDistance(from, to);

        assertEquals(0.0, bd.bearingDegrees(), DELTA);
        assertTrue(bd.distanceMeters() > 0.0);
    }

    @Test
    void bearingIsNinetyDueEastAtTheEquator() {
        GeoPosition from = new GeoPosition(0.0, 0.0, null);
        GeoPosition to = new GeoPosition(0.0, 10.0, null);

        BearingDistance bd = GeoProjection.bearingDistance(from, to);

        assertEquals(90.0, bd.bearingDegrees(), DELTA);
    }

    @Test
    void bearingIsOneEightyDueSouth() {
        GeoPosition from = new GeoPosition(0.0, 0.0, null);
        GeoPosition to = new GeoPosition(-10.0, 0.0, null);

        BearingDistance bd = GeoProjection.bearingDistance(from, to);

        assertEquals(180.0, bd.bearingDegrees(), DELTA);
    }

    @Test
    void bearingIsTwoSeventyDueWestAtTheEquator() {
        GeoPosition from = new GeoPosition(0.0, 0.0, null);
        GeoPosition to = new GeoPosition(0.0, -10.0, null);

        BearingDistance bd = GeoProjection.bearingDistance(from, to);

        assertEquals(270.0, bd.bearingDegrees(), DELTA);
    }

    // --- bearingDistance(): known baseline ----------------------------------------------

    @Test
    void distanceOfOneDegreeOfLatitudeMatchesKnownBaseline() {
        // One degree of latitude along a meridian is an exact great-circle arc of R * (pi/180).
        GeoPosition from = new GeoPosition(0.0, 0.0, null);
        GeoPosition to = new GeoPosition(1.0, 0.0, null);
        double expected = GeoProjection.EARTH_RADIUS_METERS * Math.toRadians(1.0);

        BearingDistance bd = GeoProjection.bearingDistance(from, to);

        assertEquals(expected, bd.distanceMeters(), 1.0);
        // Sanity check against the well-known ~111.19 km/degree figure for this earth radius.
        assertEquals(111_194.9, bd.distanceMeters(), 5.0);
    }

    @Test
    void distanceIsSymmetric() {
        GeoPosition a = new GeoPosition(50.45, 30.52, null);
        GeoPosition b = new GeoPosition(48.85, 2.35, null);

        BearingDistance aToB = GeoProjection.bearingDistance(a, b);
        BearingDistance bToA = GeoProjection.bearingDistance(b, a);

        assertEquals(aToB.distanceMeters(), bToA.distanceMeters(), 1e-6);
    }

    // --- bearingDistance(): antimeridian sanity --------------------------------------------

    @Test
    void bearingDistanceHandlesPointsAcrossTheAntimeridianWithoutNaN() {
        GeoPosition from = new GeoPosition(0.0, 179.5, null);
        GeoPosition to = new GeoPosition(0.0, -179.5, null);

        BearingDistance bd = GeoProjection.bearingDistance(from, to);

        assertFalse(Double.isNaN(bd.distanceMeters()));
        assertFalse(Double.isNaN(bd.bearingDegrees()));
        // The short way across the antimeridian is ~1 degree of longitude, not ~359.
        assertTrue(bd.distanceMeters() < GeoProjection.EARTH_RADIUS_METERS * Math.toRadians(5.0));
    }

    // --- bearingDistance(): validation -------------------------------------------------------

    @Test
    void rejectsNullFrom() {
        GeoPosition to = new GeoPosition(0.0, 0.0, null);

        assertThrows(IllegalArgumentException.class, () -> GeoProjection.bearingDistance(null, to));
    }

    @Test
    void rejectsNullTo() {
        GeoPosition from = new GeoPosition(0.0, 0.0, null);

        assertThrows(IllegalArgumentException.class, () -> GeoProjection.bearingDistance(from, null));
    }
}
