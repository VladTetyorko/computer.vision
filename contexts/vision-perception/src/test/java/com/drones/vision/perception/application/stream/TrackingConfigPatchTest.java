package com.drones.vision.perception.application.stream;

import com.drones.vision.perception.domain.model.TargetLock;
import com.drones.vision.perception.domain.model.TrackingConfig;
import com.drones.vision.perception.domain.model.TrackingMode;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The per-field fold of docs/plans/done/TRACKING-PLAN.md &sect;4.D, tested without a running stream — the whole
 * point of putting it on the patch record rather than inside {@code DefaultStreamService}.
 */
class TrackingConfigPatchTest {

    private final AtomicLong lockSeq = new AtomicLong();

    private TrackingConfig fold(TrackingConfigPatch patch, TrackingConfig current) {
        return patch.foldOnto(current, lockSeq::incrementAndGet);
    }

    private static TrackingConfig running() {
        return new TrackingConfig(TrackingMode.FOLLOW, "lk", 5000, 25, 45, 60, 2,
                new TargetLock(4, 7L, null, null, false));
    }

    @Test
    void nothingChangesNothing() {
        assertEquals(running(), fold(TrackingConfigPatch.NOTHING, running()));
        assertEquals(0L, lockSeq.get(), "a patch without a lock burns no sequence number");
    }

    @Test
    void everySingleKnobFoldsIndependently() {
        TrackingConfig current = running();

        assertEquals(TrackingMode.ASSOCIATE,
                fold(only(TrackingMode.ASSOCIATE, null, null, null, null, null, null), current).mode());
        assertEquals("ncc", fold(only(null, "ncc", null, null, null, null, null), current).engineId());
        assertEquals(1500, fold(only(null, null, 1500, null, null, null, null), current).verifyEveryMillis());
        assertEquals(10, fold(only(null, null, null, 10, null, null, null), current).followFps());
        assertEquals(80, fold(only(null, null, null, null, 80, null, null), current).redetectIouPercent());
        assertEquals(12, fold(only(null, null, null, null, null, 12, null), current).maxAgeFrames());
        assertEquals(9, fold(only(null, null, null, null, null, null, 9), current).minHits());
    }

    @Test
    void aSingleKnobPatchLeavesEveryOtherKnobExactlyAsItWas() {
        TrackingConfig folded = fold(only(null, "ncc", null, null, null, null, null), running());

        assertEquals(new TrackingConfig(TrackingMode.FOLLOW, "ncc", 5000, 25, 45, 60, 2, running().lock()), folded);
    }

    @Test
    void anAbsentLockKeepsTheRunningOneAndAPresentOneIsStampedServerSide() {
        assertEquals(running().lock(), fold(only(null, null, 1500, null, null, null, null), running()).lock(),
                "a cadence tweak must never drop the operator's target");

        TargetLock stamped = fold(new TrackingConfigPatch(null, null, null, null, null, null, null,
                new TargetLock(0, 9L, null, null, false)), running()).lock();

        assertEquals(1L, stamped.lockSeq(), "the client's 0 is discarded; the server allocates");
        assertEquals(9L, stamped.trackId());
    }

    @Test
    void aReleaseIsAnExplicitLockFormNotAnOmission() {
        TrackingConfig folded = fold(new TrackingConfigPatch(null, null, null, null, null, null, null,
                new TargetLock(0, null, null, null, true)), running());

        assertTrue(folded.lock().release());
        assertEquals(1L, folded.lock().lockSeq());
        assertEquals(TrackingMode.FOLLOW, folded.mode(), "releasing a target is not leaving FOLLOW");
    }

    @Test
    void anEmptyEngineIdIsARealValueMeaningTheServerPicks() {
        assertEquals("", fold(only(null, "", null, null, null, null, null), running()).engineId());
    }

    @Test
    void anOutOfRangeValueIsRejectedByTheDomainRecordRatherThanDuplicatedHere() {
        assertThrows(IllegalArgumentException.class,
                () -> fold(only(null, null, 0, null, null, null, null), running()));
    }

    @Test
    void foldingOntoTrackingOffIsHowANewStreamPicksUpADeploymentSeed() {
        TrackingConfig seeded = fold(only(TrackingMode.ASSOCIATE, null, 2500, 20, null, null, null),
                TrackingConfig.off());

        assertEquals(TrackingMode.ASSOCIATE, seeded.mode());
        assertEquals(2500, seeded.verifyEveryMillis());
        assertEquals(20, seeded.followFps());
        assertEquals(TrackingConfig.DEFAULT_MIN_HITS, seeded.minHits(),
                "a knob the deployment does not own keeps the domain's own literal -- one number, one owner");
        assertNull(seeded.lock());
    }

    @Test
    void theIdentityPatchIsShared() {
        assertSame(TrackingConfigPatch.NOTHING, TrackingConfigPatch.NOTHING);
        assertNull(TrackingConfigPatch.NOTHING.mode());
        assertNull(TrackingConfigPatch.NOTHING.lock());
    }

    private static TrackingConfigPatch only(TrackingMode mode, String engineId, Integer verifyEveryMillis,
                                             Integer followFps, Integer redetectIouPercent, Integer maxAgeFrames,
                                             Integer minHits) {
        return new TrackingConfigPatch(mode, engineId, verifyEveryMillis, followFps, redetectIouPercent, maxAgeFrames,
                minHits, null);
    }
}
