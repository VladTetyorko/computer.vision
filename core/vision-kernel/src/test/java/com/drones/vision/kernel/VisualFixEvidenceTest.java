package com.drones.vision.kernel;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class VisualFixEvidenceTest {

    private static VisualFixEvidence valid() {
        return new VisualFixEvidence(10, 50, 20, 0.4, 0.3, 2.0, true, true, 5, 50.0, true, 10.0, 5, 1.0);
    }

    @Test
    void storesEveryField() {
        VisualFixEvidence evidence = valid();

        assertEquals(10, evidence.candidateCount());
        assertEquals(50, evidence.matchCount());
        assertEquals(20, evidence.inlierCount());
        assertEquals(0.4, evidence.inlierRatio());
        assertEquals(0.3, evidence.rerankMargin());
        assertEquals(2.0, evidence.reprojectionRmsPixels());
        assertEquals(true, evidence.rectified());
        assertEquals(true, evidence.cellCalibrated());
        assertEquals(5, evidence.supportingFrames());
        assertEquals(50.0, evidence.baselineMeters());
        assertEquals(true, evidence.sequenceConverged());
        assertEquals(10.0, evidence.sequenceSpreadMeters());
        assertEquals(5, evidence.sequenceUpdates());
        assertEquals(1.0, evidence.osmPrior());
    }

    @Test
    void rejectsNegativeCandidateCount() {
        assertThrows(IllegalArgumentException.class,
                () -> new VisualFixEvidence(-1, 50, 20, 0.4, 0.3, 2.0, true, true, 5, 50.0, true, 10.0, 5, 1.0));
    }

    @Test
    void rejectsNegativeMatchCount() {
        assertThrows(IllegalArgumentException.class,
                () -> new VisualFixEvidence(10, -1, 20, 0.4, 0.3, 2.0, true, true, 5, 50.0, true, 10.0, 5, 1.0));
    }

    @Test
    void rejectsNegativeInlierCount() {
        assertThrows(IllegalArgumentException.class,
                () -> new VisualFixEvidence(10, 50, -1, 0.4, 0.3, 2.0, true, true, 5, 50.0, true, 10.0, 5, 1.0));
    }

    @Test
    void rejectsNegativeInlierRatio() {
        assertThrows(IllegalArgumentException.class,
                () -> new VisualFixEvidence(10, 50, 20, -0.1, 0.3, 2.0, true, true, 5, 50.0, true, 10.0, 5, 1.0));
    }

    @Test
    void rejectsNonFiniteRerankMargin() {
        assertThrows(IllegalArgumentException.class,
                () -> new VisualFixEvidence(10, 50, 20, 0.4, Double.NaN, 2.0, true, true, 5, 50.0, true, 10.0, 5,
                        1.0));
    }

    @Test
    void rejectsNegativeReprojectionRms() {
        assertThrows(IllegalArgumentException.class,
                () -> new VisualFixEvidence(10, 50, 20, 0.4, 0.3, -1.0, true, true, 5, 50.0, true, 10.0, 5, 1.0));
    }

    @Test
    void rejectsNegativeSupportingFrames() {
        assertThrows(IllegalArgumentException.class,
                () -> new VisualFixEvidence(10, 50, 20, 0.4, 0.3, 2.0, true, true, -1, 50.0, true, 10.0, 5, 1.0));
    }

    @Test
    void rejectsNegativeBaselineMeters() {
        assertThrows(IllegalArgumentException.class,
                () -> new VisualFixEvidence(10, 50, 20, 0.4, 0.3, 2.0, true, true, 5, -1.0, true, 10.0, 5, 1.0));
    }

    @Test
    void rejectsNegativeSequenceSpreadMeters() {
        assertThrows(IllegalArgumentException.class,
                () -> new VisualFixEvidence(10, 50, 20, 0.4, 0.3, 2.0, true, true, 5, 50.0, true, -1.0, 5, 1.0));
    }

    @Test
    void rejectsNegativeSequenceUpdates() {
        assertThrows(IllegalArgumentException.class,
                () -> new VisualFixEvidence(10, 50, 20, 0.4, 0.3, 2.0, true, true, 5, 50.0, true, 10.0, -1, 1.0));
    }

    @Test
    void rejectsNegativeOsmPrior() {
        assertThrows(IllegalArgumentException.class,
                () -> new VisualFixEvidence(10, 50, 20, 0.4, 0.3, 2.0, true, true, 5, 50.0, true, 10.0, 5, -1.0));
    }

    @Test
    void acceptsInertOsmPriorOfOne() {
        VisualFixEvidence evidence = valid();
        assertEquals(1.0, evidence.osmPrior());
    }
}
