package com.drones.vision.api.dto;

import com.drones.vision.kernel.BoundingBox;
import com.drones.vision.kernel.StreamId;
import com.drones.vision.perception.domain.model.EvidenceSource;
import com.drones.vision.perception.domain.model.ObjectLifecycle;
import com.drones.vision.perception.domain.model.ObjectState;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ObjectStateResponse#from} against the real Jackson setup (docs/plans/active/
 * CV-ORCHESTRATION-PLAN.md §4.5, wave W1) — proves the "a {@code null} domain group serializes as a
 * missing JSON key, never a zeroed object" rule the class javadoc promises, and that a present
 * group's own optional leaves ({@code Kinematics}'s three source boxes) follow the same rule one
 * level down.
 */
class ObjectStateResponseTest {

    private static final JsonMapper JSON = new JsonMapper();

    private static ObjectState.Kinematics kinematics(BoundingBox detectorBox, BoundingBox trackerBox,
                                                       BoundingBox predictedBox) {
        return new ObjectState.Kinematics(new BoundingBox(0.1, 0.2, 0.3, 0.4), detectorBox, trackerBox, predictedBox,
                170L, 0.21, 0.22, 0.23, 0.24, true);
    }

    @Test
    void everyGroupAbsentSerializesToMissingKeysNotNulls() {
        ObjectState state = new ObjectState(101L, ObjectLifecycle.DORMANT, StreamId.random(), null, null, null, null,
                null, null, null);

        String json = JSON.writeValueAsString(ObjectStateResponse.from(state));

        assertTrue(json.contains("\"id\":101"));
        assertTrue(json.contains("\"lifecycle\":\"DORMANT\""));
        assertTrue(json.contains("\"streamId\""));
        for (String key : List.of("identity", "kinematics", "belief", "provenance", "memory", "lock", "timing")) {
            assertFalse(json.contains("\"" + key + "\""), "expected no \"" + key + "\" key in " + json);
        }
    }

    @Test
    void everyGroupPresentRoundTripsEveryLeafByPairwiseDistinctValue() {
        ObjectState.Identity identity = new ObjectState.Identity("elected", "raw",
                List.of(new ObjectState.LabelCandidate("person", 0.81)), 82);
        ObjectState.Kinematics kinematics = kinematics(new BoundingBox(0.05, 0.06, 0.07, 0.08), null, null);
        ObjectState.Belief belief = new ObjectState.Belief(0.31, 0.32, 0.33, 340L);
        ObjectState.Provenance provenance =
                new ObjectState.Provenance(EvidenceSource.MEMORY, List.of("detect.full"), 0.41, true);
        ObjectState.MemoryFacts memory = new ObjectState.MemoryFacts(true, 0.51, 520L, 53, 0.52);
        ObjectState.LockFacts lock = new ObjectState.LockFacts(false, 60L);
        ObjectState.Timing timing = new ObjectState.Timing(700L, 800L, 900L, 71, 72, 73);
        ObjectState state = new ObjectState(101L, ObjectLifecycle.COASTING, StreamId.random(), identity, kinematics,
                belief, provenance, memory, lock, timing);

        String json = JSON.writeValueAsString(ObjectStateResponse.from(state));

        assertTrue(json.contains("\"label\":\"elected\""));
        assertTrue(json.contains("\"labelRaw\":\"raw\""));
        assertTrue(json.contains("\"weight\":0.81"));
        assertTrue(json.contains("\"detectorBox\""));
        assertTrue(json.contains("\"horizonMillis\":170"));
        assertTrue(json.contains("\"confidenceRaw\":0.31"));
        assertTrue(json.contains("\"source\":\"MEMORY\""));
        assertTrue(json.contains("\"recovered\":true"));
        assertTrue(json.contains("\"lockSeqApplied\":60"));
        assertTrue(json.contains("\"ageFrames\":71"));
        // Kinematics's own optional leaves: trackerBox/predictedBox produced nothing this frame.
        assertFalse(json.contains("\"trackerBox\""));
        assertFalse(json.contains("\"predictedBox\""));
    }

    @Test
    void kinematicsOmitsIndividualSourceBoxesTheyDoNotClaim() {
        ObjectState.Kinematics onlyElectedBox = kinematics(null, null, null);

        String json = JSON.writeValueAsString(ObjectStateResponse.Kinematics.from(onlyElectedBox));

        assertTrue(json.contains("\"box\""));
        assertFalse(json.contains("\"detectorBox\""));
        assertFalse(json.contains("\"trackerBox\""));
        assertFalse(json.contains("\"predictedBox\""));
    }

    @Test
    void idAndStreamIdMapToTheWireShapeExactly() {
        StreamId streamId = StreamId.random();
        ObjectState state = new ObjectState(7L, ObjectLifecycle.TENTATIVE, streamId, null, null, null, null, null,
                null, null);

        ObjectStateResponse response = ObjectStateResponse.from(state);

        assertEquals(7L, response.id());
        assertEquals(ObjectLifecycle.TENTATIVE, response.lifecycle());
        assertEquals(streamId.value().toString(), response.streamId());
    }
}
