package com.drones.vision.kernel;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FixedCameraGeoTest {

    // Matches GeoProjectionTest's own DELTA: a full spherical project()+bearingDistance() round
    // trip accumulates trig rounding beyond 1e-9 for non-cardinal headings.
    private static final double DEGREES_DELTA = 1e-6;
    // Loose enough for great-circle vs. simple-trig sanity checks over small ranges (meters) —
    // the same tolerance GeoProjectionTest uses for the same reason.
    private static final double METERS_DELTA = 0.5;

    // Deliberately permissive: isolates whichever refusal path a test means to exercise from the
    // other two.
    private static final FixedCameraGeoSettings PERMISSIVE =
            new FixedCameraGeoSettings(1.0, 1_000_000.0, 0.5, 1_000_000.0);

    // --- project(): center-pixel delegation matches hand-computed ray -------------------------

    @Test
    void centerPixelAtFortyFiveDegreePitchMatchesHandComputedRangeAndBearing() {
        // A pixel exactly at the frame's boresight (u=v=0.5) carries zero pixel offset, so the
        // projected ray must equal the camera's own yaw/pitch verbatim -- the same 45-degree,
        // ground-range-equals-altitude case GeoProjectionTest pins for the ray->ground half alone.
        GeoPosition camera = new GeoPosition(10.0, 20.0, null);
        FixedCameraPose pose = new FixedCameraPose(camera, 100.0, 0.0, 45.0, 90.0);
        BoundingBox boundsAtCenter = new BoundingBox(0.4, 0.3, 0.2, 0.2); // bottom-centre (0.5, 0.5)

        Optional<GroundFix> fix = FixedCameraGeo.project(pose, 1000, 1000, boundsAtCenter, PERMISSIVE);

        assertTrue(fix.isPresent());
        double expectedRangeMeters = 100.0 / Math.tan(Math.toRadians(45.0)); // == 100.0
        assertEquals(expectedRangeMeters, fix.get().rangeMeters(), METERS_DELTA);

        BearingDistance actual = GeoProjection.bearingDistance(camera, fix.get().position());
        assertEquals(0.0, actual.bearingDegrees(), DEGREES_DELTA, "zero pixel offset keeps the camera's own yaw");
        assertEquals(expectedRangeMeters, actual.distanceMeters(), METERS_DELTA);

        // Error radius by hand (D6): sigma = 0.5 deg; sin(45deg)^2 = 0.5, so downrange = 200*sigma
        // and crossrange = range*sigma = 100*sigma; downrange is larger.
        double sigmaRadians = Math.toRadians(0.5);
        double expectedErrorRadius = 100.0 * sigmaRadians / (Math.sin(Math.toRadians(45.0)) * Math.sin(Math.toRadians(45.0)));
        assertEquals(expectedErrorRadius, fix.get().errorRadiusMeters(), 1e-6);
    }

    @Test
    void nadirIshCenterPixelMatchesHandComputedSteepRange() {
        GeoPosition camera = new GeoPosition(1.0, 2.0, null);
        FixedCameraPose pose = new FixedCameraPose(camera, 50.0, 200.0, 80.0, 60.0);
        BoundingBox boundsAtCenter = new BoundingBox(0.45, 0.2, 0.1, 0.3); // bottom-centre (0.5, 0.5)

        Optional<GroundFix> fix = FixedCameraGeo.project(pose, 800, 600, boundsAtCenter, PERMISSIVE);

        assertTrue(fix.isPresent());
        double expectedRangeMeters = 50.0 / Math.tan(Math.toRadians(80.0));
        assertEquals(expectedRangeMeters, fix.get().rangeMeters(), METERS_DELTA);

        BearingDistance actual = GeoProjection.bearingDistance(camera, fix.get().position());
        assertEquals(200.0, actual.bearingDegrees(), DEGREES_DELTA);
        assertEquals(expectedRangeMeters, actual.distanceMeters(), METERS_DELTA);
    }

    // --- project(): off-center pixel shifts both axes, hand-computed ---------------------------

    @Test
    void offCenterBottomCentreShiftsBothAzimuthAndDepressionByTheHandComputedOffset() {
        GeoPosition camera = new GeoPosition(5.0, 7.0, null);
        FixedCameraPose pose = new FixedCameraPose(camera, 30.0, 200.0, 10.0, 90.0); // hfov/2 == 45deg, tan == 1
        BoundingBox box = new BoundingBox(0.6, 0.55, 0.3, 0.2); // bottom-centre (0.75, 0.75)

        Optional<GroundFix> fix = FixedCameraGeo.project(pose, 1200, 1200, box, PERMISSIVE); // square: aspect == 1

        assertTrue(fix.isPresent());
        // Per FixedCameraGeo's javadoc formula: offset = atan((2u-1) * tan(hfov/2)); here
        // (2*0.75-1) == 0.5 and tan(45deg) == 1, on both axes since aspect == 1.
        double offsetDegrees = Math.toDegrees(Math.atan(0.5 * Math.tan(Math.toRadians(45.0))));
        double expectedAzimuth = 200.0 + offsetDegrees;
        double expectedDepression = 10.0 + offsetDegrees;
        double expectedRangeMeters = 30.0 / Math.tan(Math.toRadians(expectedDepression));

        assertEquals(expectedRangeMeters, fix.get().rangeMeters(), METERS_DELTA);
        BearingDistance actual = GeoProjection.bearingDistance(camera, fix.get().position());
        assertEquals(expectedAzimuth, actual.bearingDegrees(), 1e-6);
        assertEquals(expectedRangeMeters, actual.distanceMeters(), METERS_DELTA);
    }

    // --- project(): bounding-box bottom-centre convention ---------------------------------------

    @Test
    void onlyTheBoxsBottomCentreDeterminesTheGroundContactPointNotItsTopOrHeight() {
        GeoPosition camera = new GeoPosition(0.0, 0.0, null);
        FixedCameraPose pose = new FixedCameraPose(camera, 40.0, 90.0, 20.0, 70.0);
        // Same horizontal center (x + width/2 == 0.5) and same bottom edge (y + height == 0.5);
        // wildly different top edge and height otherwise.
        BoundingBox shortBoxNearBottom = new BoundingBox(0.4, 0.3, 0.2, 0.2);
        BoundingBox tallBoxSpanningTheFrame = new BoundingBox(0.4, 0.1, 0.2, 0.4);

        Optional<GroundFix> fixFromShortBox = FixedCameraGeo.project(pose, 1000, 1000, shortBoxNearBottom, PERMISSIVE);
        Optional<GroundFix> fixFromTallBox = FixedCameraGeo.project(pose, 1000, 1000, tallBoxSpanningTheFrame, PERMISSIVE);

        assertTrue(fixFromShortBox.isPresent());
        assertTrue(fixFromTallBox.isPresent());
        assertEquals(fixFromShortBox.get().position().latitude(), fixFromTallBox.get().position().latitude(), DEGREES_DELTA);
        assertEquals(fixFromShortBox.get().position().longitude(), fixFromTallBox.get().position().longitude(), DEGREES_DELTA);
        assertEquals(fixFromShortBox.get().rangeMeters(), fixFromTallBox.get().rangeMeters(), 1e-9);
    }

    @Test
    void wideningTheBoxSymmetricallyAroundItsCenterDoesNotChangeTheProjectedAzimuth() {
        // Widening left and right by the same amount keeps x + width/2 fixed, so the horizontal
        // pixel offset -- and therefore the azimuth -- must not move.
        GeoPosition camera = new GeoPosition(0.0, 0.0, null);
        FixedCameraPose pose = new FixedCameraPose(camera, 40.0, 90.0, 20.0, 70.0);
        BoundingBox narrowBox = new BoundingBox(0.45, 0.4, 0.1, 0.1);
        BoundingBox widenedBox = new BoundingBox(0.3, 0.4, 0.4, 0.1);

        Optional<GroundFix> fixFromNarrow = FixedCameraGeo.project(pose, 1000, 1000, narrowBox, PERMISSIVE);
        Optional<GroundFix> fixFromWidened = FixedCameraGeo.project(pose, 1000, 1000, widenedBox, PERMISSIVE);

        assertTrue(fixFromNarrow.isPresent());
        assertTrue(fixFromWidened.isPresent());
        BearingDistance narrowBearing = GeoProjection.bearingDistance(camera, fixFromNarrow.get().position());
        BearingDistance widenedBearing = GeoProjection.bearingDistance(camera, fixFromWidened.get().position());
        assertEquals(narrowBearing.bearingDegrees(), widenedBearing.bearingDegrees(), 1e-6);
    }

    // --- project(): refusal 1 -- the horizon guard (D6) ------------------------------------------

    @Test
    void depressionBelowTheConfiguredGuardIsRefused() {
        FixedCameraPose pose = new FixedCameraPose(new GeoPosition(0.0, 0.0, null), 10.0, 0.0, 0.9, 60.0);
        BoundingBox centerBox = new BoundingBox(0.45, 0.4, 0.1, 0.1); // offset 0, so depression == pitch
        FixedCameraGeoSettings guardOfOneDegree = new FixedCameraGeoSettings(1.0, 1_000_000.0, 0.5, 1_000_000.0);

        Optional<GroundFix> fix = FixedCameraGeo.project(pose, 1000, 1000, centerBox, guardOfOneDegree);

        assertTrue(fix.isEmpty(), "0.9deg depression is below the 1.0deg guard -- honest refusal, not a coordinate");
    }

    @Test
    void aCameraPitchedAboveTheHorizonIsRefused() {
        // Negative depression: the ray points above horizontal and never meets flat ground at all.
        FixedCameraPose pose = new FixedCameraPose(new GeoPosition(0.0, 0.0, null), 10.0, 0.0, -5.0, 60.0);
        BoundingBox centerBox = new BoundingBox(0.45, 0.4, 0.1, 0.1);

        Optional<GroundFix> fix = FixedCameraGeo.project(pose, 1000, 1000, centerBox, PERMISSIVE);

        assertTrue(fix.isEmpty());
    }

    @Test
    void depressionExactlyAtTheGuardIsNotRefused() {
        FixedCameraPose pose = new FixedCameraPose(new GeoPosition(0.0, 0.0, null), 10.0, 0.0, 1.0, 60.0);
        BoundingBox centerBox = new BoundingBox(0.45, 0.4, 0.1, 0.1);
        FixedCameraGeoSettings guardOfOneDegree = new FixedCameraGeoSettings(1.0, 1_000_000.0, 0.5, 1_000_000.0);

        Optional<GroundFix> fix = FixedCameraGeo.project(pose, 1000, 1000, centerBox, guardOfOneDegree);

        assertTrue(fix.isPresent(), "the guard is a strict less-than: exactly at the guard still gets a fix");
    }

    @Test
    void combinedDepressionAboveNinetyDegreesIsRefusedNotThrown() {
        // pitch(89) + offset(atan(tan(85deg)) == 85, since (2v-1) == 1 exactly at v == 1.0) == 174deg
        // -- past GeoProjection.project's (0,90] contract. FixedCameraGeo must refuse this itself,
        // never let GeoProjection.project throw for what is, at the pixel level, an honest refusal.
        FixedCameraPose pose = new FixedCameraPose(new GeoPosition(0.0, 0.0, null), 10.0, 0.0, 89.0, 170.0);
        BoundingBox boxTouchingTheBottomEdge = new BoundingBox(0.45, 0.9, 0.1, 0.1); // bottom-centre v == 1.0

        Optional<GroundFix> fix = FixedCameraGeo.project(pose, 1000, 1000, boxTouchingTheBottomEdge, PERMISSIVE);

        assertTrue(fix.isEmpty());
    }

    // --- project(): refusal 2 -- beyond the configured max range (D6) ---------------------------

    @Test
    void rangeBeyondTheConfiguredCeilingIsRefused() {
        FixedCameraPose pose = new FixedCameraPose(new GeoPosition(0.0, 0.0, null), 10.0, 0.0, 2.0, 60.0);
        BoundingBox centerBox = new BoundingBox(0.45, 0.4, 0.1, 0.1);
        double handComputedRange = 10.0 / Math.tan(Math.toRadians(2.0)); // ~286m
        FixedCameraGeoSettings tightRangeCeiling =
                new FixedCameraGeoSettings(1.0, handComputedRange - 1.0, 0.5, 1_000_000.0);

        Optional<GroundFix> fix = FixedCameraGeo.project(pose, 1000, 1000, centerBox, tightRangeCeiling);

        assertTrue(fix.isEmpty());
    }

    @Test
    void rangeAtOrBelowTheConfiguredCeilingIsNotRefusedOnRangeAlone() {
        FixedCameraPose pose = new FixedCameraPose(new GeoPosition(0.0, 0.0, null), 10.0, 0.0, 2.0, 60.0);
        BoundingBox centerBox = new BoundingBox(0.45, 0.4, 0.1, 0.1);
        double handComputedRange = 10.0 / Math.tan(Math.toRadians(2.0));
        FixedCameraGeoSettings looseRangeCeiling =
                new FixedCameraGeoSettings(1.0, handComputedRange + 1.0, 0.5, 1_000_000.0);

        Optional<GroundFix> fix = FixedCameraGeo.project(pose, 1000, 1000, centerBox, looseRangeCeiling);

        assertTrue(fix.isPresent());
    }

    // --- project(): refusal 3 -- error radius beyond the configured ceiling (D6) ----------------

    @Test
    void errorRadiusBeyondTheConfiguredCeilingIsRefused() {
        FixedCameraPose pose = new FixedCameraPose(new GeoPosition(0.0, 0.0, null), 100.0, 0.0, 3.0, 60.0);
        BoundingBox centerBox = new BoundingBox(0.45, 0.4, 0.1, 0.1);
        double sigmaRadians = Math.toRadians(2.0); // a deliberately large angular error
        double sinDepression = Math.sin(Math.toRadians(3.0));
        double handComputedErrorRadius = 100.0 * sigmaRadians / (sinDepression * sinDepression);
        FixedCameraGeoSettings tightErrorCeiling =
                new FixedCameraGeoSettings(1.0, 1_000_000.0, 2.0, handComputedErrorRadius - 1.0);

        Optional<GroundFix> fix = FixedCameraGeo.project(pose, 1000, 1000, centerBox, tightErrorCeiling);

        assertTrue(fix.isEmpty());
    }

    // --- project(): error radius grows monotonically toward the horizon (D6) --------------------

    @Test
    void errorRadiusGrowsMonotonicallyAsDepressionApproachesTheGuard() {
        double[] depressionsSteepToShallow = {85.0, 60.0, 30.0, 15.0, 5.0, 2.0};
        double previousErrorRadius = -1.0;

        for (double depressionDegrees : depressionsSteepToShallow) {
            FixedCameraPose pose =
                    new FixedCameraPose(new GeoPosition(0.0, 0.0, null), 50.0, 0.0, depressionDegrees, 60.0);
            BoundingBox centerBox = new BoundingBox(0.45, 0.4, 0.1, 0.1);

            Optional<GroundFix> fix = FixedCameraGeo.project(pose, 1000, 1000, centerBox, PERMISSIVE);

            assertTrue(fix.isPresent(), "depression " + depressionDegrees + " should not be refused");
            double errorRadius = fix.get().errorRadiusMeters();
            assertTrue(errorRadius > previousErrorRadius,
                    "error radius must grow as depression " + depressionDegrees + " approaches the horizon");
            previousErrorRadius = errorRadius;
        }
    }

    // --- project(): validation --------------------------------------------------------------

    @Test
    void rejectsNullPose() {
        BoundingBox box = new BoundingBox(0.45, 0.4, 0.1, 0.1);
        assertThrows(IllegalArgumentException.class, () -> FixedCameraGeo.project(null, 1000, 1000, box, PERMISSIVE));
    }

    @Test
    void rejectsNullBoundingBox() {
        FixedCameraPose pose = new FixedCameraPose(new GeoPosition(0.0, 0.0, null), 10.0, 0.0, 45.0, 60.0);
        assertThrows(IllegalArgumentException.class, () -> FixedCameraGeo.project(pose, 1000, 1000, null, PERMISSIVE));
    }

    @Test
    void rejectsNullSettings() {
        FixedCameraPose pose = new FixedCameraPose(new GeoPosition(0.0, 0.0, null), 10.0, 0.0, 45.0, 60.0);
        BoundingBox box = new BoundingBox(0.45, 0.4, 0.1, 0.1);
        assertThrows(IllegalArgumentException.class, () -> FixedCameraGeo.project(pose, 1000, 1000, box, null));
    }

    @Test
    void rejectsNonPositiveImageDimensions() {
        FixedCameraPose pose = new FixedCameraPose(new GeoPosition(0.0, 0.0, null), 10.0, 0.0, 45.0, 60.0);
        BoundingBox box = new BoundingBox(0.45, 0.4, 0.1, 0.1);

        assertThrows(IllegalArgumentException.class, () -> FixedCameraGeo.project(pose, 0, 1000, box, PERMISSIVE));
        assertThrows(IllegalArgumentException.class, () -> FixedCameraGeo.project(pose, 1000, 0, box, PERMISSIVE));
        assertThrows(IllegalArgumentException.class, () -> FixedCameraGeo.project(pose, -1, 1000, box, PERMISSIVE));
    }

    @Test
    void neverThrowsForTheRefusalCasesItDocuments() {
        // A regression guard for the D6 contract itself: refusal is Optional.empty(), never an
        // exception, across all three documented paths plus the depression>90 edge case.
        FixedCameraPose belowGuard = new FixedCameraPose(new GeoPosition(0.0, 0.0, null), 10.0, 0.0, 0.5, 60.0);
        FixedCameraPose pastNinety = new FixedCameraPose(new GeoPosition(0.0, 0.0, null), 10.0, 0.0, 89.0, 170.0);
        BoundingBox centerBox = new BoundingBox(0.45, 0.4, 0.1, 0.1);
        BoundingBox bottomEdgeBox = new BoundingBox(0.45, 0.9, 0.1, 0.1);

        assertFalse(FixedCameraGeo.project(belowGuard, 1000, 1000, centerBox, PERMISSIVE).isPresent());
        assertFalse(FixedCameraGeo.project(pastNinety, 1000, 1000, bottomEdgeBox, PERMISSIVE).isPresent());
    }
}
