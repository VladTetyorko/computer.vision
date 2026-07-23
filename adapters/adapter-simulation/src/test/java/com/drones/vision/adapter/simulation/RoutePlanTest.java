package com.drones.vision.adapter.simulation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class RoutePlanTest {

    private static final double EARTH_RADIUS_METERS = 6_371_000.0;

    // --- parse() matrix -------------------------------------------------------------

    @Test
    void parseReturnsNullWhenRouteOptionIsNullOrBlank() {
        assertNull(RoutePlan.parse(null, null));
        assertNull(RoutePlan.parse("", null));
        assertNull(RoutePlan.parse("   ", null));
    }

    @Test
    void parseReturnsNullForASinglePointRoute() {
        assertNull(RoutePlan.parse("10.0,20.0", null));
    }

    @Test
    void parseReturnsNullForAnUnparseableNumber() {
        assertNull(RoutePlan.parse("10.0,20.0;not-a-number,20.1", null));
    }

    @Test
    void parseReturnsNullForAWronglyShapedPoint() {
        assertNull(RoutePlan.parse("10.0,20.0;10.1", null)); // only 1 field
        assertNull(RoutePlan.parse("10.0,20.0;10.1,20.1,5.0,99.0", null)); // 4 fields
    }

    @Test
    void parseAcceptsTwoAndThreeFieldPointsMixed() {
        RoutePlan plan = RoutePlan.parse("10.0,20.0;10.1,20.1,150.0", null);
        assertNotNull(plan);
        RoutePlan.Position start = plan.positionAt(0);
        assertEquals(10.0, start.latitude(), 1e-9);
        assertEquals(20.0, start.longitude(), 1e-9);
        assertNull(start.altitudeMeters(), "start waypoint gave no altitude, so it must stay null");
    }

    // --- interpolation: position/bearing/altitude ------------------------------------

    @Test
    void positionAtStartAndEndOfASimpleTwoPointRoute() {
        RoutePlan plan = RoutePlan.parse("10.0,20.0;10.0,20.01", "once");
        double length = segmentLengthMeters(10.0, 20.0, 10.0, 20.01);

        RoutePlan.Position start = plan.positionAt(0);
        assertEquals(10.0, start.latitude(), 1e-9);
        assertEquals(20.0, start.longitude(), 1e-9);

        RoutePlan.Position end = plan.positionAt(length);
        assertEquals(10.0, end.latitude(), 1e-9);
        assertEquals(20.01, end.longitude(), 1e-9);
    }

    @Test
    void positionAtInterpolatesLinearlyAtHalfDistance() {
        RoutePlan plan = RoutePlan.parse("10.0,20.0;12.0,22.0", "once");
        double length = segmentLengthMeters(10.0, 20.0, 12.0, 22.0);

        RoutePlan.Position midpoint = plan.positionAt(length / 2.0);
        assertEquals(11.0, midpoint.latitude(), 1e-6);
        assertEquals(21.0, midpoint.longitude(), 1e-6);
    }

    @Test
    void bearingPointsDueNorthForANorthHeadingSegmentAndDueEastForAnEastHeadingSegment() {
        RoutePlan north = RoutePlan.parse("0.0,0.0;1.0,0.0", "once");
        assertEquals(0.0, north.positionAt(0).headingDegrees(), 1e-6);

        RoutePlan east = RoutePlan.parse("0.0,0.0;0.0,1.0", "once");
        assertEquals(90.0, east.positionAt(0).headingDegrees(), 1e-6);
    }

    @Test
    void altitudeInterpolatesWhenBothWaypointsGiveItAndIsNullWhenEitherOmitsIt() {
        RoutePlan bothGiven = RoutePlan.parse("10.0,20.0,100.0;10.0,20.01,200.0", "once");
        double length = segmentLengthMeters(10.0, 20.0, 10.0, 20.01);
        assertEquals(150.0, bothGiven.positionAt(length / 2.0).altitudeMeters(), 1e-6);

        RoutePlan oneOmits = RoutePlan.parse("10.0,20.0,100.0;10.0,20.01", "once");
        assertNull(oneOmits.positionAt(length / 2.0).altitudeMeters());
    }

    // --- route modes --------------------------------------------------------------

    @Test
    void onceModeHoldsAtTheEndPastTheTotalDistance() {
        RoutePlan plan = RoutePlan.parse("10.0,20.0;10.0,20.01", "once");
        double length = segmentLengthMeters(10.0, 20.0, 10.0, 20.01);

        RoutePlan.Position atEnd = plan.positionAt(length);
        RoutePlan.Position wayPastEnd = plan.positionAt(length * 50);

        assertEquals(atEnd.latitude(), wayPastEnd.latitude(), 1e-9);
        assertEquals(atEnd.longitude(), wayPastEnd.longitude(), 1e-9);
        assertEquals(atEnd.headingDegrees(), wayPastEnd.headingDegrees(), 1e-9);
    }

    @Test
    void loopModeWrapsViaTheClosingLegBackToTheStart() {
        RoutePlan plan = RoutePlan.parse("0.0,0.0;0.0,1.0", null); // default mode is loop
        double legLength = segmentLengthMeters(0.0, 0.0, 0.0, 1.0);
        double lapLength = legLength * 2; // out-leg + closing leg, same magnitude

        RoutePlan.Position atFullLap = plan.positionAt(lapLength);
        assertEquals(0.0, atFullLap.latitude(), 1e-6);
        assertEquals(0.0, atFullLap.longitude(), 1e-6);

        // periodicity: any distance plus a full lap must land on the same point.
        RoutePlan.Position quarterLap = plan.positionAt(legLength / 2.0);
        RoutePlan.Position quarterLapPlusOneLap = plan.positionAt(legLength / 2.0 + lapLength);
        assertEquals(quarterLap.latitude(), quarterLapPlusOneLap.latitude(), 1e-9);
        assertEquals(quarterLap.longitude(), quarterLapPlusOneLap.longitude(), 1e-9);
    }

    @Test
    void bounceModeRetracesBackwardsWithAReversedHeadingThenRepeats() {
        RoutePlan plan = RoutePlan.parse("0.0,0.0;0.0,1.0", "bounce");
        double length = segmentLengthMeters(0.0, 0.0, 0.0, 1.0);

        RoutePlan.Position forwardMidpoint = plan.positionAt(length / 2.0);
        RoutePlan.Position returningMidpoint = plan.positionAt(length * 1.5); // past the end, retracing

        assertEquals(forwardMidpoint.latitude(), returningMidpoint.latitude(), 1e-9,
                "the physical location at the mirrored distance is the same either direction");
        assertEquals(forwardMidpoint.longitude(), returningMidpoint.longitude(), 1e-9);
        assertEquals((forwardMidpoint.headingDegrees() + 180.0) % 360.0, returningMidpoint.headingDegrees(), 1e-9,
                "heading must reverse while retracing");

        // A full there-and-back cycle (2x path length) must land back at the start, heading forward again.
        RoutePlan.Position afterFullCycle = plan.positionAt(length * 2);
        assertEquals(0.0, afterFullCycle.latitude(), 1e-9);
        assertEquals(0.0, afterFullCycle.longitude(), 1e-9);
        assertEquals(plan.positionAt(0).headingDegrees(), afterFullCycle.headingDegrees(), 1e-9);
    }

    @Test
    void unknownModeOptionFallsBackToLoopAndDefaultModeIsLoopWhenOptionIsAbsent() {
        RoutePlan explicitBadMode = RoutePlan.parse("0.0,0.0;0.0,1.0", "not-a-real-mode");
        RoutePlan absentMode = RoutePlan.parse("0.0,0.0;0.0,1.0", null);
        double legLength = segmentLengthMeters(0.0, 0.0, 0.0, 1.0);
        double lapLength = legLength * 2;

        // Both must behave like loop: wrapping to the start after a full lap.
        assertEquals(0.0, explicitBadMode.positionAt(lapLength).latitude(), 1e-6);
        assertEquals(0.0, absentMode.positionAt(lapLength).latitude(), 1e-6);
    }

    /** Same equirectangular approximation {@link RoutePlan} itself uses internally, for test-side expectations. */
    private static double segmentLengthMeters(double lat1, double lon1, double lat2, double lon2) {
        double meanLatitudeRadians = Math.toRadians((lat1 + lat2) / 2.0);
        double northMeters = Math.toRadians(lat2 - lat1) * EARTH_RADIUS_METERS;
        double eastMeters = Math.toRadians(lon2 - lon1) * EARTH_RADIUS_METERS * Math.cos(meanLatitudeRadians);
        return Math.sqrt(northMeters * northMeters + eastMeters * eastMeters);
    }
}
