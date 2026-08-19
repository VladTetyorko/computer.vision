package com.drones.vision.api.live;

import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.StreamId;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plain unit tests (no Spring context) for {@link LiveAndPollDetectionDemand} — see that class's
 * own javadoc for why its SSE-half collaborator is a {@code Predicate<AssetId>} rather than the
 * concrete {@link LiveUpdateRegistry}, which is what keeps every scenario here (including the
 * throwing-predicate one) a plain lambda instead of needing a mock of a {@code final} class.
 */
class LiveAndPollDetectionDemandTest {

    private static final Duration POLL_TTL = Duration.ofSeconds(10);

    @Test
    void detectionWantedIsTrueWhenTheSsePredicateSaysAnAssetIsWatched() {
        LiveAndPollDetectionDemand demand = new LiveAndPollDetectionDemand(assetId -> true, POLL_TTL);

        assertTrue(demand.detectionWanted(StreamId.random(), AssetId.random()));
    }

    @Test
    void detectionWantedIsFalseWhenNeitherHalfHasAnythingToReport() {
        LiveAndPollDetectionDemand demand = new LiveAndPollDetectionDemand(assetId -> false, POLL_TTL);

        assertFalse(demand.detectionWanted(StreamId.random(), AssetId.random()));
    }

    @Test
    void aNullAssetIdSkipsTheSseHalfEntirely() {
        // A device-only stream with no resolved asset (DetectionDemandPort's own contract) --
        // the predicate must never even be consulted, since there is nothing for it to check.
        AtomicReference<Boolean> predicateWasCalled = new AtomicReference<>(false);
        LiveAndPollDetectionDemand demand = new LiveAndPollDetectionDemand(assetId -> {
            predicateWasCalled.set(true);
            return true;
        }, POLL_TTL);

        assertFalse(demand.detectionWanted(StreamId.random(), null));
        assertFalse(predicateWasCalled.get(), "predicate must not be consulted for a null assetId");
    }

    @Test
    void touchedStreamCountsAsDemandedEvenWithNoSseWatcher() {
        StreamId streamId = StreamId.random();
        LiveAndPollDetectionDemand demand = new LiveAndPollDetectionDemand(assetId -> false, POLL_TTL);

        demand.touched(streamId);

        assertTrue(demand.detectionWanted(streamId, AssetId.random()));
    }

    @Test
    void touchingOneStreamDoesNotDemandAnother() {
        StreamId touchedStream = StreamId.random();
        StreamId untouchedStream = StreamId.random();
        LiveAndPollDetectionDemand demand = new LiveAndPollDetectionDemand(assetId -> false, POLL_TTL);

        demand.touched(touchedStream);

        assertTrue(demand.detectionWanted(touchedStream, null));
        assertFalse(demand.detectionWanted(untouchedStream, null));
    }

    @Test
    void aPollJustInsideTheTtlStillCountsAsDemand() {
        StreamId streamId = StreamId.random();
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-08-15T10:00:00Z"));
        LiveAndPollDetectionDemand demand = new LiveAndPollDetectionDemand(assetId -> false, POLL_TTL, now::get);

        demand.touched(streamId);
        now.set(now.get().plus(POLL_TTL).minusMillis(1));

        assertTrue(demand.detectionWanted(streamId, null), "one millisecond inside the TTL still counts");
    }

    @Test
    void aPollExactlyAtTheTtlBoundaryHasExpired() {
        StreamId streamId = StreamId.random();
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-08-15T10:00:00Z"));
        LiveAndPollDetectionDemand demand = new LiveAndPollDetectionDemand(assetId -> false, POLL_TTL, now::get);

        demand.touched(streamId);
        now.set(now.get().plus(POLL_TTL));

        assertFalse(demand.detectionWanted(streamId, null), "the TTL boundary itself has already expired");
    }

    @Test
    void aThrowingPredicateIsSwallowedRatherThanPropagated() {
        // DetectionDemandPort's own contract: a periodically-scheduled caller must never have one
        // failing evaluation take down every later one.
        LiveAndPollDetectionDemand demand = new LiveAndPollDetectionDemand(assetId -> {
            throw new IllegalStateException("registry blew up");
        }, POLL_TTL);

        assertDoesNotThrow(() -> demand.detectionWanted(StreamId.random(), AssetId.random()));
    }

    @Test
    void aThrowingPredicateFailsOpenSoOneBrokenLookupNeverGatesDetectionOffEverywhere() {
        // The DIRECTION of the swallow, pinned separately from the fact of it. "We could not
        // determine whether anyone is watching" must answer "wanted", never the confident negative:
        // returning false here would gate detection off for every stream at once AND report
        // DetectionState.IDLE_NO_VIEWERS to an operator who is demonstrably watching. Matches every
        // other failure decision in this feature (an absent port gates nothing; the pipeline's own
        // demand flag initialises true) -- docs/plans/active/CV-DEMAND-PLAN.md §3.2/§3.3.
        LiveAndPollDetectionDemand demand = new LiveAndPollDetectionDemand(assetId -> {
            throw new IllegalStateException("registry blew up");
        }, POLL_TTL);

        assertTrue(demand.detectionWanted(StreamId.random(), AssetId.random()),
                "an undeterminable lookup must fail open, not claim nobody is watching");
    }

    // ---- D9 third OR-term: a calibrated fixed camera (docs/plans/active/FIXED-CAMERA-GEO-PLAN.md) ----

    @Test
    void detectionWantedIsTrueWhenTheAssetHasACameraPoseEvenWithNoSseWatcherOrPoll() {
        LiveAndPollDetectionDemand demand =
                new LiveAndPollDetectionDemand(assetId -> false, POLL_TTL, assetId -> true);

        assertTrue(demand.detectionWanted(StreamId.random(), AssetId.random()));
    }

    @Test
    void theTwoArgConstructorDefaultsHasCameraPoseToFalsePreservingPreG4Behaviour() {
        // The two pre-existing public constructors must reproduce this class's exact pre-G4
        // behaviour when the geo feature doesn't exist on a build, or is off (the default) --
        // CvWiring#detectionDemandPort resolves hasCameraPose from an ObjectProvider that defaults
        // to assetId -> false in exactly that situation, so this constructor must match.
        LiveAndPollDetectionDemand demand = new LiveAndPollDetectionDemand(assetId -> false, POLL_TTL);

        assertFalse(demand.detectionWanted(StreamId.random(), AssetId.random()));
    }

    @Test
    void aNullAssetIdSkipsTheCameraPoseHalfEntirelyJustLikeTheSseHalf() {
        AtomicReference<Boolean> hasCameraPoseWasCalled = new AtomicReference<>(false);
        LiveAndPollDetectionDemand demand = new LiveAndPollDetectionDemand(assetId -> false, POLL_TTL, assetId -> {
            hasCameraPoseWasCalled.set(true);
            return true;
        });

        assertFalse(demand.detectionWanted(StreamId.random(), null));
        assertFalse(hasCameraPoseWasCalled.get(), "hasCameraPose must not be consulted for a null assetId");
    }

    @Test
    void aThrowingHasCameraPosePredicateFailsOpenJustLikeTheSseHalf() {
        LiveAndPollDetectionDemand demand = new LiveAndPollDetectionDemand(assetId -> false, POLL_TTL, assetId -> {
            throw new IllegalStateException("track-projection-runner blew up");
        });

        assertTrue(demand.detectionWanted(StreamId.random(), AssetId.random()),
                "the same fail-open contract applies to every OR-term, not just the SSE half");
    }
}
