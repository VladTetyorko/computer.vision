package com.drones.vision.application.stream;

import com.drones.vision.domain.model.TargetLock;
import com.drones.vision.domain.model.TrackingConfig;
import com.drones.vision.domain.model.TrackingMode;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import com.drones.vision.application.device.DeviceEdit;

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

    @Test
    void theFiveArgConvenienceConstructorLeavesTrackingUntouched() {
        // docs/TRACKING-PLAN.md §4.D: absent tracking = leave tracking as-is, and every pre-T3
        // call site (vision-api's UpdateStreamConfigRequest#toPatch among them) keeps compiling.
        assertNull(new PipelineConfigPatch(0.5, null, null, null, null).tracking());
        assertNull(PipelineConfigPatch.NOTHING.tracking());
    }

    @Test
    void aPresentTrackingConfigIsCarriedThroughVerbatim() {
        TrackingConfig requested = new TrackingConfig(TrackingMode.FOLLOW, "lk", 2000, 15, 30, 30, 3,
                new TargetLock(0, 7L, null, null, false));

        PipelineConfigPatch patch = new PipelineConfigPatch(null, null, null, null, null, requested);

        assertEquals(requested, patch.tracking());
        assertEquals(0L, patch.tracking().lock().lockSeq(),
                "a client leaves lockSeq at 0; DefaultStreamService allocates the real one");
    }
}
