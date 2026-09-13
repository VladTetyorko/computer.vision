package com.drones.vision.perception.domain.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** {@link TrackingKnobPatch}: the profile-owned subset of {@link TrackingConfig}'s knobs, folded per field. */
class TrackingKnobPatchTest {

    @Test
    void nothingFoldsToAValueIdenticalTrackingConfig() {
        TrackingConfig below = TrackingConfig.defaults();

        TrackingConfig resolved = TrackingKnobPatch.NOTHING.foldOnto(below);

        assertEquals(below, resolved);
    }

    @Test
    void everyPresentKnobWinsAndEveryUnownedKnobAlwaysComesFromBelow() {
        TrackingConfig below = TrackingConfig.defaults();
        TrackingKnobPatch patch = new TrackingKnobPatch(TrackingMode.FOLLOW, "ncc", 3, 500, 20);

        TrackingConfig resolved = patch.foldOnto(below);

        assertEquals(TrackingMode.FOLLOW, resolved.mode());
        assertEquals("ncc", resolved.engineId());
        assertEquals(3, resolved.capabilityLevel());
        assertEquals(500, resolved.verifyEveryMillis());
        assertEquals(20, resolved.followFps());
        // The five knobs this patch has no field for at all always come from below, regardless of
        // what else the patch sets.
        assertEquals(below.redetectIouPercent(), resolved.redetectIouPercent());
        assertEquals(below.maxAgeFrames(), resolved.maxAgeFrames());
        assertEquals(below.minHits(), resolved.minHits());
        assertEquals(below.reupdateMaxGapMillis(), resolved.reupdateMaxGapMillis());
        assertEquals(below.lock(), resolved.lock());
    }

    @Test
    void oneSetKnobLeavesEveryOtherOwnedKnobAsBelowHasIt() {
        TrackingConfig below = TrackingConfig.defaults();
        TrackingKnobPatch patch = new TrackingKnobPatch(null, null, null, 750, null);

        TrackingConfig resolved = patch.foldOnto(below);

        assertEquals(750, resolved.verifyEveryMillis());
        assertEquals(below.mode(), resolved.mode());
        assertEquals(below.engineId(), resolved.engineId());
        assertEquals(below.capabilityLevel(), resolved.capabilityLevel());
        assertEquals(below.followFps(), resolved.followFps());
    }

    @Test
    void rejectsNullBelow() {
        assertThrows(IllegalArgumentException.class, () -> TrackingKnobPatch.NOTHING.foldOnto(null));
    }
}
