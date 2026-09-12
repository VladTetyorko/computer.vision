package com.drones.vision.perception.domain.model;

import com.drones.vision.kernel.BoundingBox;
import com.drones.vision.kernel.StreamId;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ObjectStateTest {

    private static final BoundingBox BOX = new BoundingBox(0.1, 0.1, 0.2, 0.2);

    @Test
    void rejectsUntrackedSentinelAndNegativeId() {
        assertThrows(IllegalArgumentException.class, () -> objectState(0L));
        assertThrows(IllegalArgumentException.class, () -> objectState(-1L));
    }

    @Test
    void rejectsNullLifecycleOrStreamId() {
        assertThrows(IllegalArgumentException.class,
                () -> new ObjectState(1L, null, StreamId.random(), null, null, null, null, null, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> new ObjectState(1L, ObjectLifecycle.CONFIRMED, null, null, null, null, null, null, null,
                        null));
    }

    @Test
    void everyNestedGroupMayBeNull() {
        ObjectState state = new ObjectState(1L, ObjectLifecycle.DORMANT, StreamId.random(), null, null, null, null,
                null, null, null);

        assertEquals(null, state.identity());
        assertEquals(null, state.kinematics());
        assertEquals(null, state.belief());
        assertEquals(null, state.provenance());
        assertEquals(null, state.memory());
        assertEquals(null, state.lock());
        assertEquals(null, state.timing());
    }

    // -- LabelCandidate --------------------------------------------------

    @Test
    void labelCandidateRejectsNullLabel() {
        assertThrows(IllegalArgumentException.class, () -> new ObjectState.LabelCandidate(null, 1.0));
    }

    @Test
    void labelCandidateRejectsNonFiniteWeight() {
        assertThrows(IllegalArgumentException.class,
                () -> new ObjectState.LabelCandidate("person", Double.NaN));
        assertThrows(IllegalArgumentException.class,
                () -> new ObjectState.LabelCandidate("person", Double.POSITIVE_INFINITY));
    }

    // -- Identity ----------------------------------------------------------

    @Test
    void identityRejectsNullLabelOrLabelRawOrCandidates() {
        assertThrows(IllegalArgumentException.class, () -> new ObjectState.Identity(null, "raw", List.of(), 0));
        assertThrows(IllegalArgumentException.class, () -> new ObjectState.Identity("label", null, List.of(), 0));
        assertThrows(IllegalArgumentException.class, () -> new ObjectState.Identity("label", "raw", null, 0));
    }

    @Test
    void identityAllowsBlankLabelRaw() {
        ObjectState.Identity identity = new ObjectState.Identity("person", "", List.of(), 0);

        assertEquals("", identity.labelRaw());
    }

    @Test
    void identityRejectsNegativeStability() {
        assertThrows(IllegalArgumentException.class, () -> new ObjectState.Identity("label", "raw", List.of(), -1));
    }

    @Test
    void identityCandidatesAreDefensivelyCopied() {
        List<ObjectState.LabelCandidate> candidates = new ArrayList<>();
        candidates.add(new ObjectState.LabelCandidate("person", 0.9));

        ObjectState.Identity identity = new ObjectState.Identity("person", "person", candidates, 0);
        candidates.add(new ObjectState.LabelCandidate("dog", 0.1));

        assertEquals(1, identity.candidates().size());
        assertThrows(UnsupportedOperationException.class,
                () -> identity.candidates().add(new ObjectState.LabelCandidate("cat", 0.1)));
    }

    // -- Kinematics ----------------------------------------------------------

    @Test
    void kinematicsRejectsNullBox() {
        assertThrows(IllegalArgumentException.class,
                () -> new ObjectState.Kinematics(null, null, null, null, 0L, 0.0, 0.0, 0.0, 0.0, false));
    }

    @Test
    void kinematicsRejectsNegativeHorizon() {
        assertThrows(IllegalArgumentException.class,
                () -> new ObjectState.Kinematics(BOX, null, null, null, -1L, 0.0, 0.0, 0.0, 0.0, false));
    }

    @Test
    void kinematicsRejectsNonFiniteMotionFields() {
        assertThrows(IllegalArgumentException.class,
                () -> new ObjectState.Kinematics(BOX, null, null, null, 0L, Double.NaN, 0.0, 0.0, 0.0, false));
        assertThrows(IllegalArgumentException.class,
                () -> new ObjectState.Kinematics(BOX, null, null, null, 0L, 0.0, Double.NaN, 0.0, 0.0, false));
        assertThrows(IllegalArgumentException.class,
                () -> new ObjectState.Kinematics(BOX, null, null, null, 0L, 0.0, 0.0, Double.NaN, 0.0, false));
        assertThrows(IllegalArgumentException.class,
                () -> new ObjectState.Kinematics(BOX, null, null, null, 0L, 0.0, 0.0, 0.0, Double.NaN, false));
    }

    @Test
    void kinematicsAllowsAllOptionalBoxesAbsent() {
        ObjectState.Kinematics kinematics =
                new ObjectState.Kinematics(BOX, null, null, null, 0L, 0.0, 0.0, 0.0, 0.0, false);

        assertEquals(null, kinematics.detectorBox());
        assertEquals(null, kinematics.trackerBox());
        assertEquals(null, kinematics.predictedBox());
    }

    // -- Belief ----------------------------------------------------------

    @Test
    void beliefRejectsOutOfRangeConfidenceOrExistence() {
        assertThrows(IllegalArgumentException.class, () -> new ObjectState.Belief(-0.01, 0.5, 0.5, 0L));
        assertThrows(IllegalArgumentException.class, () -> new ObjectState.Belief(1.01, 0.5, 0.5, 0L));
        assertThrows(IllegalArgumentException.class, () -> new ObjectState.Belief(0.5, -0.01, 0.5, 0L));
        assertThrows(IllegalArgumentException.class, () -> new ObjectState.Belief(0.5, 1.01, 0.5, 0L));
        assertThrows(IllegalArgumentException.class, () -> new ObjectState.Belief(0.5, 0.5, -0.01, 0L));
        assertThrows(IllegalArgumentException.class, () -> new ObjectState.Belief(0.5, 0.5, 1.01, 0L));
    }

    @Test
    void beliefRejectsNegativeSinceConfirmed() {
        assertThrows(IllegalArgumentException.class, () -> new ObjectState.Belief(0.5, 0.5, 0.5, -1L));
    }

    // -- Provenance ----------------------------------------------------------

    @Test
    void provenanceRejectsNullSourceOrContributors() {
        assertThrows(IllegalArgumentException.class,
                () -> new ObjectState.Provenance(null, List.of(), 0.0, false));
        assertThrows(IllegalArgumentException.class,
                () -> new ObjectState.Provenance(EvidenceSource.DETECTOR, null, 0.0, false));
    }

    @Test
    void provenanceRejectsNegativeAssocCost() {
        assertThrows(IllegalArgumentException.class,
                () -> new ObjectState.Provenance(EvidenceSource.DETECTOR, List.of(), -0.01, false));
    }

    @Test
    void provenanceContributorsAreDefensivelyCopied() {
        List<String> contributors = new ArrayList<>();
        contributors.add("detect.full");

        ObjectState.Provenance provenance = new ObjectState.Provenance(EvidenceSource.DETECTOR, contributors, 0.0,
                false);
        contributors.add("assoc.cost");

        assertEquals(1, provenance.contributors().size());
        assertThrows(UnsupportedOperationException.class, () -> provenance.contributors().add("late"));
    }

    // -- MemoryFacts ----------------------------------------------------------

    @Test
    void memoryFactsRejectsOutOfRangeScores() {
        assertThrows(IllegalArgumentException.class, () -> new ObjectState.MemoryFacts(true, -0.01, 0L, 0, 0.0));
        assertThrows(IllegalArgumentException.class, () -> new ObjectState.MemoryFacts(true, 1.01, 0L, 0, 0.0));
        assertThrows(IllegalArgumentException.class, () -> new ObjectState.MemoryFacts(true, 0.5, 0L, 0, -0.01));
        assertThrows(IllegalArgumentException.class, () -> new ObjectState.MemoryFacts(true, 0.5, 0L, 0, 1.01));
    }

    @Test
    void memoryFactsRejectsNegativeDormantMsOrGalleryMatches() {
        assertThrows(IllegalArgumentException.class, () -> new ObjectState.MemoryFacts(true, 0.5, -1L, 0, 0.5));
        assertThrows(IllegalArgumentException.class, () -> new ObjectState.MemoryFacts(true, 0.5, 0L, -1, 0.5));
    }

    // -- LockFacts ----------------------------------------------------------

    @Test
    void lockFactsRejectsNegativeLockSeq() {
        assertThrows(IllegalArgumentException.class, () -> new ObjectState.LockFacts(true, -1L));
    }

    // -- Timing ----------------------------------------------------------

    @Test
    void timingRejectsNegativeEpochMillis() {
        assertThrows(IllegalArgumentException.class, () -> new ObjectState.Timing(-1L, 0L, 0L, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new ObjectState.Timing(0L, -1L, 0L, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new ObjectState.Timing(0L, 0L, -1L, 0, 0, 0));
    }

    @Test
    void timingRejectsNegativeCounters() {
        assertThrows(IllegalArgumentException.class, () -> new ObjectState.Timing(0L, 0L, 0L, -1, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new ObjectState.Timing(0L, 0L, 0L, 0, -1, 0));
        assertThrows(IllegalArgumentException.class, () -> new ObjectState.Timing(0L, 0L, 0L, 0, 0, -1));
    }

    // -- fixture -------------------------------------------------------------

    @Test
    void everyFieldDistinctFixtureConstructsAndHoldsDistinctValues() {
        ObjectState state = ObjectStateFixtures.everyFieldDistinct();

        assertEquals(101L, state.id());
        assertEquals(ObjectLifecycle.COASTING, state.lifecycle());
        assertEquals("elected-label", state.identity().label());
        assertEquals("raw-label", state.identity().labelRaw());
        assertEquals(2, state.identity().candidates().size());
        assertEquals(170L, state.kinematics().horizonMillis());
        assertTrue(state.kinematics().motionCompensated());
        assertEquals(0.31, state.belief().confidenceRaw());
        assertEquals(EvidenceSource.MEMORY, state.provenance().source());
        assertEquals(53, state.memory().galleryMatches());
        assertEquals(60L, state.lock().lockSeqApplied());
        assertEquals(71, state.timing().ageFrames());
        assertEquals(72, state.timing().hits());
        assertEquals(73, state.timing().misses());
    }

    private static ObjectState objectState(long id) {
        return new ObjectState(id, ObjectLifecycle.CONFIRMED, StreamId.random(), null, null, null, null, null, null,
                null);
    }
}
