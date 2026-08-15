package com.drones.vision.kernel;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeoProjectionTest {

    private static final double DELTA = 1e-6;
    // Loose enough for great-circle vs. simple-trig sanity checks over small ranges (meters).
    private static final double METERS_DELTA = 0.5;

    private static Telemetry telemetry(Double altitudeMeters, Double headingDegrees, Double aglMeters,
                                        Attitude attitude) {
        return new Telemetry(DeviceId.random(), Instant.now(), 50.0, 30.0, altitudeMeters, headingDegrees,
                null, Map.of(), null, aglMeters, attitude, null);
    }

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

    // --- aimFrom(): bearing precedence ------------------------------------------------------

    @Test
    void aimFromPrefersGimbalYawOverHeadingForBearing() {
        Attitude attitude = new Attitude(null, null, null, null, null, 77.0);
        Telemetry telemetry = telemetry(100.0, 10.0, null, attitude);

        GeoProjection.CameraAim aim = GeoProjection.aimFrom(telemetry, 45.0);

        assertEquals(77.0, aim.bearingDegrees(), DELTA, "gimbal yaw wins over airframe heading");
    }

    @Test
    void aimFromFallsBackToHeadingWhenNoGimbalYaw() {
        Telemetry telemetry = telemetry(100.0, 10.0, null, null);

        GeoProjection.CameraAim aim = GeoProjection.aimFrom(telemetry, 45.0);

        assertEquals(10.0, aim.bearingDegrees(), DELTA);
    }

    @Test
    void aimFromFallsBackToHeadingWhenAttitudeHasNoGimbalYaw() {
        Attitude attitude = new Attitude(1.0, 2.0, 3.0, null, null, null);
        Telemetry telemetry = telemetry(100.0, 10.0, null, attitude);

        GeoProjection.CameraAim aim = GeoProjection.aimFrom(telemetry, 45.0);

        assertEquals(10.0, aim.bearingDegrees(), DELTA);
    }

    @Test
    void aimFromThrowsWhenNeitherGimbalYawNorHeadingIsAvailable() {
        Telemetry telemetry = telemetry(100.0, null, null, null);

        assertThrows(IllegalArgumentException.class, () -> GeoProjection.aimFrom(telemetry, 45.0));
    }

    // --- aimFrom(): depression precedence ---------------------------------------------------

    @Test
    void aimFromUsesNegatedGimbalPitchWhenItLandsWithinRange() {
        Attitude attitude = new Attitude(null, null, null, null, -30.0, 90.0);
        Telemetry telemetry = telemetry(100.0, 10.0, null, attitude);

        GeoProjection.CameraAim aim = GeoProjection.aimFrom(telemetry, 45.0);

        assertEquals(30.0, aim.depressionDegrees(), DELTA);
    }

    @Test
    void aimFromAcceptsGimbalPitchStraightDownAsNadir() {
        Attitude attitude = new Attitude(null, null, null, null, -90.0, 90.0);
        Telemetry telemetry = telemetry(100.0, 10.0, null, attitude);

        GeoProjection.CameraAim aim = GeoProjection.aimFrom(telemetry, 45.0);

        assertEquals(90.0, aim.depressionDegrees(), DELTA);
    }

    @Test
    void aimFromFallsBackWhenGimbalPitchIsLevel() {
        Attitude attitude = new Attitude(null, null, null, null, 0.0, 90.0);
        Telemetry telemetry = telemetry(100.0, 10.0, null, attitude);

        GeoProjection.CameraAim aim = GeoProjection.aimFrom(telemetry, 45.0);

        assertEquals(45.0, aim.depressionDegrees(), DELTA, "level gimbal cannot intersect the ground ahead");
    }

    @Test
    void aimFromFallsBackWhenGimbalPitchPointsUpward() {
        Attitude attitude = new Attitude(null, null, null, null, 15.0, 90.0);
        Telemetry telemetry = telemetry(100.0, 10.0, null, attitude);

        GeoProjection.CameraAim aim = GeoProjection.aimFrom(telemetry, 45.0);

        assertEquals(45.0, aim.depressionDegrees(), DELTA, "upward-pointed gimbal cannot intersect the ground ahead");
    }

    @Test
    void aimFromFallsBackWhenAttitudeHasNoGimbalPitch() {
        Attitude attitude = new Attitude(null, null, null, null, null, 90.0);
        Telemetry telemetry = telemetry(100.0, 10.0, null, attitude);

        GeoProjection.CameraAim aim = GeoProjection.aimFrom(telemetry, 45.0);

        assertEquals(45.0, aim.depressionDegrees(), DELTA);
    }

    @Test
    void aimFromFallsBackWhenAttitudeIsAbsent() {
        Telemetry telemetry = telemetry(100.0, 10.0, null, null);

        GeoProjection.CameraAim aim = GeoProjection.aimFrom(telemetry, 45.0);

        assertEquals(45.0, aim.depressionDegrees(), DELTA);
    }

    // --- aimFrom(): AGL precedence ----------------------------------------------------------

    @Test
    void aimFromPrefersAglMetersOverAltitudeMeters() {
        Telemetry telemetry = telemetry(180.0, 10.0, 60.0, null);

        GeoProjection.CameraAim aim = GeoProjection.aimFrom(telemetry, 45.0);

        assertEquals(60.0, aim.aglMeters(), DELTA);
    }

    @Test
    void aimFromFallsBackToAltitudeMetersWhenAglIsAbsent() {
        Telemetry telemetry = telemetry(180.0, 10.0, null, null);

        GeoProjection.CameraAim aim = GeoProjection.aimFrom(telemetry, 45.0);

        assertEquals(180.0, aim.aglMeters(), DELTA, "AMSL fallback carries today's site-elevation error, by design");
    }

    @Test
    void aimFromThrowsWhenNeitherAglNorAltitudeIsAvailable() {
        Telemetry telemetry = telemetry(null, 10.0, null, null);

        assertThrows(IllegalArgumentException.class, () -> GeoProjection.aimFrom(telemetry, 45.0));
    }

    // --- aimFrom(): measured flag -------------------------------------------------------------

    @Test
    void aimFromReportsMeasuredTrueOnlyWhenBothDepressionAndAglAreReal() {
        Attitude attitude = new Attitude(null, null, null, null, -30.0, 77.0);
        Telemetry telemetry = telemetry(180.0, 10.0, 60.0, attitude);

        GeoProjection.CameraAim aim = GeoProjection.aimFrom(telemetry, 45.0);

        assertTrue(aim.measured());
    }

    @Test
    void aimFromReportsMeasuredFalseWhenDepressionIsMeasuredButAglIsMissing() {
        Attitude attitude = new Attitude(null, null, null, null, -30.0, 77.0);
        Telemetry telemetry = telemetry(180.0, 10.0, null, attitude);

        GeoProjection.CameraAim aim = GeoProjection.aimFrom(telemetry, 45.0);

        assertFalse(aim.measured(), "AGL fell back to AMSL altitude, so the aim is not fully measured");
    }

    @Test
    void aimFromReportsMeasuredFalseWhenAglIsPresentButDepressionFellBack() {
        Telemetry telemetry = telemetry(180.0, 10.0, 60.0, null);

        GeoProjection.CameraAim aim = GeoProjection.aimFrom(telemetry, 45.0);

        assertFalse(aim.measured(), "depression fell back to the guessed default, so the aim is not fully measured");
    }

    @Test
    void aimFromReportsMeasuredFalseWhenBothFellBack() {
        Telemetry telemetry = telemetry(180.0, 10.0, null, null);

        GeoProjection.CameraAim aim = GeoProjection.aimFrom(telemetry, 45.0);

        assertFalse(aim.measured());
    }

    @Test
    void aimFromReportsMeasuredFalseWhenBearingIsFromHeadingButRestIsMeasured() {
        // Bearing sourced from airframe heading (no gimbal yaw) does not by itself downgrade
        // "measured" — but here the gimbal pitch is also absent, so depression fell back too.
        Attitude attitude = new Attitude(null, null, null, null, null, null);
        Telemetry telemetry = telemetry(180.0, 10.0, 60.0, attitude);

        GeoProjection.CameraAim aim = GeoProjection.aimFrom(telemetry, 45.0);

        assertEquals(10.0, aim.bearingDegrees());
        assertFalse(aim.measured(), "no gimbal pitch reading means depression fell back, regardless of AGL");
    }

    // --- aimFrom(): validation -----------------------------------------------------------------

    @Test
    void aimFromRejectsNullTelemetry() {
        assertThrows(IllegalArgumentException.class, () -> GeoProjection.aimFrom(null, 45.0));
    }

    // --- CameraAim: validation ------------------------------------------------------------------

    @Test
    void cameraAimRejectsNonFiniteBearing() {
        assertThrows(IllegalArgumentException.class,
                () -> new GeoProjection.CameraAim(Double.NaN, 45.0, 100.0, false));
        assertThrows(IllegalArgumentException.class,
                () -> new GeoProjection.CameraAim(Double.POSITIVE_INFINITY, 45.0, 100.0, false));
    }

    @Test
    void cameraAimRejectsOutOfRangeDepression() {
        assertThrows(IllegalArgumentException.class,
                () -> new GeoProjection.CameraAim(0.0, 0.0, 100.0, false));
        assertThrows(IllegalArgumentException.class,
                () -> new GeoProjection.CameraAim(0.0, 90.1, 100.0, false));
        assertThrows(IllegalArgumentException.class,
                () -> new GeoProjection.CameraAim(0.0, -10.0, 100.0, false));
    }

    @Test
    void cameraAimRejectsNegativeAgl() {
        assertThrows(IllegalArgumentException.class,
                () -> new GeoProjection.CameraAim(0.0, 45.0, -1.0, false));
    }

    @Test
    void cameraAimAcceptsDepressionExactlyNinety() {
        GeoProjection.CameraAim aim = new GeoProjection.CameraAim(0.0, 90.0, 100.0, true);

        assertEquals(90.0, aim.depressionDegrees());
    }

    // --- project(GeoPosition, CameraAim): delegation and equivalence ---------------------------

    @Test
    void projectWithCameraAimMatchesTheFourArgOverload() {
        GeoPosition drone = new GeoPosition(50.0, 30.0, 100.0);
        GeoProjection.CameraAim aim = new GeoProjection.CameraAim(35.0, 40.0, 120.0, true);

        GeoPosition viaAim = GeoProjection.project(drone, aim);
        GeoPosition viaFourArg = GeoProjection.project(drone, aim.bearingDegrees(), aim.aglMeters(),
                aim.depressionDegrees());

        assertEquals(viaFourArg.latitude(), viaAim.latitude(), DELTA);
        assertEquals(viaFourArg.longitude(), viaAim.longitude(), DELTA);
    }

    @Test
    void projectWithCameraAimRejectsNullDrone() {
        GeoProjection.CameraAim aim = new GeoProjection.CameraAim(0.0, 45.0, 100.0, false);

        assertThrows(IllegalArgumentException.class, () -> GeoProjection.project(null, aim));
    }

    @Test
    void projectWithCameraAimRejectsNullAim() {
        GeoPosition drone = new GeoPosition(50.0, 30.0, 100.0);

        assertThrows(IllegalArgumentException.class, () -> GeoProjection.project(drone, (GeoProjection.CameraAim) null));
    }

    // --- G6: the AMSL fallback path reproduces today's exact numbers --------------------------

    @Test
    void aimFromOnBareTelemetryReproducesTodaysGoldenValueExactly() {
        // Same scenario as goldenValueDueNorthFortyFiveDegreeDepression above, but resolved through
        // aimFrom() from a Telemetry sample that reports none of the new fields (no attitude, no
        // agl) — proving the new code path is behaviorally identical to the pre-V1 call for a
        // device that reports nothing new (G6).
        GeoPosition drone = new GeoPosition(10.0, 20.0, 100.0);
        double expectedLat = 10.0 + Math.toDegrees(100.0 / GeoProjection.EARTH_RADIUS_METERS);
        Telemetry bareTelemetry = telemetry(100.0, 0.0, null, null);

        GeoProjection.CameraAim aim = GeoProjection.aimFrom(bareTelemetry, 45.0);
        GeoPosition ground = GeoProjection.project(drone, aim);

        assertFalse(aim.measured());
        assertEquals(100.0, aim.aglMeters(), DELTA, "falls back to altitudeMeters, carrying the AMSL error");
        assertEquals(45.0, aim.depressionDegrees(), DELTA);
        assertEquals(expectedLat, ground.latitude(), 1e-9);
        assertEquals(20.0, ground.longitude(), 1e-9);
    }
}
