package com.drones.vision.application;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PipelineConfigPatchTest {

    @Test
    void everyFieldIsOptionalBecauseNullMeansLeaveUnchanged() {
        PipelineConfigPatch patch = new PipelineConfigPatch(null, null, null, null, null);

        assertNull(patch.confidenceThreshold());
        assertNull(patch.inferenceFps());
        assertNull(patch.labelFilter());
        assertNull(patch.detectionEnabled());
        assertNull(patch.modelId());
    }

    @Test
    void nothingIsTheIdentityPatch() {
        assertNull(PipelineConfigPatch.NOTHING.confidenceThreshold());
        assertNull(PipelineConfigPatch.NOTHING.inferenceFps());
        assertNull(PipelineConfigPatch.NOTHING.labelFilter());
        assertNull(PipelineConfigPatch.NOTHING.detectionEnabled());
        assertNull(PipelineConfigPatch.NOTHING.modelId());
    }

    @Test
    void aPresentButEmptyLabelFilterIsAcceptedAsARealValueMeaningKeepAllLabels() {
        // Unlike DeviceEdit#capabilities, an empty labelFilter is not a mistake -- it mirrors
        // PipelineConfig's own "empty label filter = all labels" semantics.
        PipelineConfigPatch patch = new PipelineConfigPatch(null, null, Set.of(), null, null);

        assertEquals(Set.of(), patch.labelFilter());
    }

    @Test
    void copiesAPresentLabelFilter() {
        Set<String> mutable = new HashSet<>(Set.of("person"));
        PipelineConfigPatch patch = new PipelineConfigPatch(null, null, mutable, null, null);

        mutable.add("car");

        assertThrows(UnsupportedOperationException.class, () -> patch.labelFilter().add("truck"));
        assertEquals(Set.of("person"), patch.labelFilter());
    }
}
