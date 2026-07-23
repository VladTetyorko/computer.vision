package com.drones.vision.application;

import com.drones.vision.domain.model.BoundingBox;
import com.drones.vision.domain.model.Detection;
import com.drones.vision.domain.model.DetectionResult;
import com.drones.vision.domain.model.ModelRef;
import com.drones.vision.domain.model.StreamId;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DetectionExtrapolatorTest {

    private static final double DELTA = 1e-9;
    private static final ModelRef MODEL = new ModelRef("yolo", "latest");
    private static final Instant T0 = Instant.parse("2024-01-01T00:00:00Z");

    private final StreamId streamId = StreamId.random();

    private DetectionResult result(long frameSequence, Instant capturedAt, Detection... detections) {
        return new DetectionResult(streamId, frameSequence, capturedAt, List.of(detections), Duration.ZERO);
    }

    private Detection detection(String label, double x, double y, double width, double height) {
        return new Detection(label, 0.9, new BoundingBox(x, y, width, height), MODEL);
    }

    private static double centerX(Detection d) {
        return d.box().x() + d.box().width() / 2.0;
    }

    private static double centerY(Detection d) {
        return d.box().y() + d.box().height() / 2.0;
    }

    @Test
    void noResultsYieldsEmptyList() {
        DetectionExtrapolator extrapolator = new DetectionExtrapolator();

        assertTrue(extrapolator.at(T0).isEmpty());
    }

    @Test
    void singleResultIsReturnedAsIsRegardlessOfRequestedTime() {
        DetectionExtrapolator extrapolator = new DetectionExtrapolator();
        Detection d = detection("person", 0.1, 0.1, 0.2, 0.2);
        DetectionResult only = result(0, T0, d);

        extrapolator.accept(only);

        assertSame(only.detections(), extrapolator.at(T0));
        assertSame(only.detections(), extrapolator.at(T0.plusSeconds(5)));
        assertSame(only.detections(), extrapolator.at(T0.minusSeconds(5)));
    }

    @Test
    void emptyLatestDetectionsYieldsEmptyListEvenWithAPreviousResult() {
        DetectionExtrapolator extrapolator = new DetectionExtrapolator();
        extrapolator.accept(result(0, T0, detection("person", 0.1, 0.1, 0.2, 0.2)));
        DetectionResult emptyLatest = result(1, T0.plusMillis(100));

        extrapolator.accept(emptyLatest);

        assertTrue(extrapolator.at(T0.plusMillis(150)).isEmpty());
    }

    @Test
    void outOfOrderAcceptIsIgnored() {
        DetectionExtrapolator extrapolator = new DetectionExtrapolator();
        Detection d5 = detection("person", 0.5, 0.5, 0.1, 0.1);
        DetectionResult result5 = result(5, T0, d5);
        extrapolator.accept(result5);

        // Lower sequence: ignored.
        extrapolator.accept(result(3, T0.plusMillis(10), detection("person", 0.9, 0.9, 0.1, 0.1)));
        // Equal sequence: also ignored (must be strictly greater).
        extrapolator.accept(result(5, T0.plusMillis(20), detection("person", 0.8, 0.8, 0.1, 0.1)));

        // Neither ignored call became "previous" -- still single-result passthrough.
        assertSame(result5.detections(), extrapolator.at(T0.plusMillis(50)));

        Detection d7 = detection("person", 0.54, 0.5, 0.1, 0.1);
        DetectionResult result7 = result(7, T0.plusMillis(100), d7);
        extrapolator.accept(result7);

        // Now a genuine previous->latest pair exists and extrapolation kicks in.
        List<Detection> at = extrapolator.at(T0.plusMillis(100));
        assertEquals(1, at.size());
        assertEquals(d7.box().x(), at.get(0).box().x(), DELTA);
    }

    @Test
    void matchedDetectionExtrapolatesAlongMeasuredVelocity() {
        DetectionExtrapolator extrapolator = new DetectionExtrapolator();
        Detection p = detection("person", 0.10, 0.10, 0.20, 0.20); // center (0.20, 0.20)
        Detection l = detection("person", 0.14, 0.10, 0.20, 0.20); // center (0.24, 0.20)
        extrapolator.accept(result(0, T0, p));
        extrapolator.accept(result(1, T0.plusMillis(100), l));

        // velocity x = (0.24 - 0.20) / 0.1s = 0.4 units/s; velocity y = 0.
        // at +50ms past L: newCenterX = 0.24 + 0.4*0.05 = 0.26 -> x = 0.26 - 0.10 = 0.16
        List<Detection> at = extrapolator.at(T0.plusMillis(150));

        assertEquals(1, at.size());
        Detection extrapolated = at.get(0);
        assertEquals(0.16, extrapolated.box().x(), DELTA);
        assertEquals(0.10, extrapolated.box().y(), DELTA);
        assertEquals(0.20, extrapolated.box().width(), DELTA);
        assertEquals(0.20, extrapolated.box().height(), DELTA);
        assertEquals("person", extrapolated.label());
        assertEquals(l.confidence(), extrapolated.confidence(), DELTA);
        assertSame(l.model(), extrapolated.model(), "Detection.model is kept from L's detection");
    }

    @Test
    void detectionBeyondTheGateIsNotMatchedAndIsReturnedAsIs() {
        DetectionExtrapolator extrapolator = new DetectionExtrapolator();
        Detection p = detection("person", 0.10, 0.10, 0.20, 0.20); // center (0.20, 0.20)
        Detection l = detection("person", 0.40, 0.10, 0.20, 0.20); // center (0.50, 0.20), distance 0.30 > 0.15
        extrapolator.accept(result(0, T0, p));
        extrapolator.accept(result(1, T0.plusMillis(100), l));

        List<Detection> at = extrapolator.at(T0.plusMillis(150));

        assertEquals(1, at.size());
        assertEquals(l, at.get(0), "unmatched L detection is returned unchanged");
    }

    @Test
    void detectionWithinTheGateDistanceMatches() {
        DetectionExtrapolator extrapolator = new DetectionExtrapolator();
        // Straight-line center distance 0.14, comfortably inside the 0.15 gate.
        Detection p = detection("person", 0.10, 0.10, 0.20, 0.20); // center (0.20, 0.20)
        Detection l = detection("person", 0.24, 0.10, 0.20, 0.20); // center (0.34, 0.20), distance 0.14
        extrapolator.accept(result(0, T0, p));
        extrapolator.accept(result(1, T0.plusMillis(100), l));

        List<Detection> at = extrapolator.at(T0.plusMillis(200)); // 100ms past L: extrapolation should move it

        assertEquals(1, at.size());
        assertTrue(Math.abs(at.get(0).box().x() - l.box().x()) > DELTA,
                "a matched detection should have moved from L's raw position");
    }

    @Test
    void detectionJustBeyondTheGateDistanceDoesNotMatch() {
        DetectionExtrapolator extrapolator = new DetectionExtrapolator();
        // Straight-line center distance 0.16, comfortably outside the 0.15 gate.
        Detection p = detection("person", 0.10, 0.10, 0.20, 0.20); // center (0.20, 0.20)
        Detection l = detection("person", 0.26, 0.10, 0.20, 0.20); // center (0.36, 0.20), distance 0.16
        extrapolator.accept(result(0, T0, p));
        extrapolator.accept(result(1, T0.plusMillis(100), l));

        List<Detection> at = extrapolator.at(T0.plusMillis(200));

        assertEquals(1, at.size());
        assertEquals(l.box().x(), at.get(0).box().x(), DELTA,
                "a detection just beyond the gate must not be extrapolated");
        assertEquals(l.box().y(), at.get(0).box().y(), DELTA);
    }

    @Test
    void greedyMatchingPrefersTheNearestPairAndLeavesTheLoserUnmatched() {
        DetectionExtrapolator extrapolator = new DetectionExtrapolator();
        Detection p1 = detection("person", 0.05, 0.10, 0.10, 0.10); // center (0.10, 0.15)
        Detection l1 = detection("person", 0.06, 0.10, 0.10, 0.10); // center (0.11, 0.15), distance 0.01
        Detection l2 = detection("person", 0.15, 0.10, 0.10, 0.10); // center (0.20, 0.15), distance 0.10
        extrapolator.accept(result(0, T0, p1));
        extrapolator.accept(result(1, T0.plusMillis(100), l1, l2));

        List<Detection> at = extrapolator.at(T0.plusMillis(200));

        assertEquals(2, at.size());
        // l1 is the closer pair, so it is the one that moved (matched); l2 stayed put (unmatched).
        assertTrue(Math.abs(at.get(0).box().x() - l1.box().x()) > DELTA, "closer detection (l1) was matched");
        assertEquals(l2.box().x(), at.get(1).box().x(), DELTA, "farther detection (l2) stayed unmatched");
    }

    @Test
    void unmatchedLabelIsReturnedAsIsAndPOnlyDetectionIsDropped() {
        DetectionExtrapolator extrapolator = new DetectionExtrapolator();
        Detection personP = detection("person", 0.10, 0.10, 0.20, 0.20);
        Detection carP = detection("car", 0.50, 0.50, 0.10, 0.10); // P-only: no counterpart in L
        Detection personL = detection("person", 0.12, 0.10, 0.20, 0.20); // matches personP
        Detection dogL = detection("dog", 0.80, 0.80, 0.10, 0.10); // no "dog" in P: unmatched
        extrapolator.accept(result(0, T0, personP, carP));
        extrapolator.accept(result(1, T0.plusMillis(100), personL, dogL));

        List<Detection> at = extrapolator.at(T0.plusMillis(100)); // exactly at L: no time to extrapolate over

        assertEquals(2, at.size(), "carP (P-only) must be dropped; personL and dogL both survive");
        assertEquals("person", at.get(0).label());
        assertEquals(dogL, at.get(1), "unmatched dog detection returned unchanged");
    }

    @Test
    void extrapolationIsCappedAtMaxExtrapolationMillisPastLatestCapturedAt() {
        DetectionExtrapolator extrapolator = new DetectionExtrapolator();
        Detection p = detection("person", 0.10, 0.10, 0.20, 0.20);
        Detection l = detection("person", 0.14, 0.10, 0.20, 0.20);
        extrapolator.accept(result(0, T0, p));
        extrapolator.accept(result(1, T0.plusMillis(100), l));

        Instant atCap = T0.plusMillis(100).plusMillis(DetectionExtrapolator.MAX_EXTRAPOLATION_MILLIS);
        List<Detection> frozenAtCap = extrapolator.at(atCap);
        List<Detection> wayBeyondCap = extrapolator.at(atCap.plusSeconds(3600));

        assertEquals(frozenAtCap, wayBeyondCap, "past the cap the output freezes at the cap's value");
        // Sanity: it did actually extrapolate away from L's raw box by the time the cap is hit.
        assertTrue(Math.abs(frozenAtCap.get(0).box().x() - l.box().x()) > DELTA);
    }

    @Test
    void clampsExtrapolatedPositionAtTheLowerFrameEdge() {
        DetectionExtrapolator extrapolator = new DetectionExtrapolator();
        Detection p = detection("person", 0.03, 0.48, 0.04, 0.04); // center (0.05, 0.50)
        Detection l = detection("person", 0.00, 0.48, 0.04, 0.04); // center (0.02, 0.50)
        extrapolator.accept(result(0, T0, p));
        extrapolator.accept(result(1, T0.plusMillis(100), l));

        // velocity x = (0.02 - 0.05) / 0.1s = -0.3 units/s; 300ms further -> delta = -0.09
        // newCenterX = 0.02 - 0.09 = -0.07 -> x = -0.07 - 0.02 = -0.09 -> clamped to 0.0
        List<Detection> at = extrapolator.at(T0.plusMillis(400));

        assertEquals(0.0, at.get(0).box().x(), DELTA);
        assertEquals(0.48, at.get(0).box().y(), DELTA);
    }

    @Test
    void clampsExtrapolatedPositionAtTheUpperFrameEdge() {
        DetectionExtrapolator extrapolator = new DetectionExtrapolator();
        Detection p = detection("person", 0.85, 0.45, 0.10, 0.10); // center (0.90, 0.50)
        Detection l = detection("person", 0.90, 0.45, 0.10, 0.10); // center (0.95, 0.50)
        extrapolator.accept(result(0, T0, p));
        extrapolator.accept(result(1, T0.plusMillis(100), l));

        // velocity x = (0.95 - 0.90) / 0.1s = 0.5 units/s; 300ms further -> delta = 0.15
        // newCenterX = 0.95 + 0.15 = 1.10 -> x = 1.10 - 0.05 = 1.05 -> clamped to 1.0
        List<Detection> at = extrapolator.at(T0.plusMillis(400));

        assertEquals(1.0, at.get(0).box().x(), DELTA);
        assertEquals(0.45, at.get(0).box().y(), DELTA);
    }

    @Test
    void zeroDeltaTBetweenPreviousAndLatestSkipsExtrapolation() {
        DetectionExtrapolator extrapolator = new DetectionExtrapolator();
        Detection p = detection("person", 0.10, 0.10, 0.20, 0.20);
        Detection l = detection("person", 0.14, 0.10, 0.20, 0.20);
        extrapolator.accept(result(0, T0, p));
        extrapolator.accept(result(1, T0, l)); // identical capturedAt: deltaSeconds == 0

        List<Detection> at = extrapolator.at(T0.plusMillis(500));

        assertEquals(l.box().x(), at.get(0).box().x(), DELTA);
    }

    @Test
    void negativeDeltaTBetweenPreviousAndLatestSkipsExtrapolation() {
        DetectionExtrapolator extrapolator = new DetectionExtrapolator();
        // frameSequence governs accept ordering, not capturedAt -- construct a latest whose
        // capturedAt is (unusually) earlier than the previous result's, and confirm the guard
        // against a negative delta still holds rather than extrapolating backwards oddly.
        Detection earlierResultDetection = detection("person", 0.10, 0.10, 0.20, 0.20);
        Detection laterSequenceDetection = detection("person", 0.14, 0.10, 0.20, 0.20);
        extrapolator.accept(result(1, T0.plusMillis(100), earlierResultDetection));
        extrapolator.accept(result(2, T0, laterSequenceDetection));

        List<Detection> at = extrapolator.at(T0.plusMillis(500));

        assertEquals(laterSequenceDetection.box().x(), at.get(0).box().x(), DELTA);
    }
}
