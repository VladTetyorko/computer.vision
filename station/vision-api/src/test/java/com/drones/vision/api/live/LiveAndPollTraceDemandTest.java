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
 * Plain unit tests (no Spring context) for {@link LiveAndPollTraceDemand} — mirrors {@code
 * LiveAndPollDetectionDemandTest}'s structure, except for the fail-CLOSED assertions, which are
 * this class's whole reason for existing separately: see {@link LiveAndPollTraceDemand}'s own
 * javadoc for why the direction is deliberately reversed from its detection-demand sibling.
 */
class LiveAndPollTraceDemandTest {

    private static final Duration POLL_TTL = Duration.ofSeconds(10);

    @Test
    void traceWantedIsTrueWhenTheSsePredicateSaysAnAssetIsWatched() {
        LiveAndPollTraceDemand demand = new LiveAndPollTraceDemand(assetId -> true, POLL_TTL);

        assertTrue(demand.traceWanted(StreamId.random(), AssetId.random()));
    }

    @Test
    void traceWantedIsFalseWhenNeitherHalfHasAnythingToReport() {
        LiveAndPollTraceDemand demand = new LiveAndPollTraceDemand(assetId -> false, POLL_TTL);

        assertFalse(demand.traceWanted(StreamId.random(), AssetId.random()));
    }

    @Test
    void aNullAssetIdSkipsTheSseHalfEntirely() {
        AtomicReference<Boolean> predicateWasCalled = new AtomicReference<>(false);
        LiveAndPollTraceDemand demand = new LiveAndPollTraceDemand(assetId -> {
            predicateWasCalled.set(true);
            return true;
        }, POLL_TTL);

        assertFalse(demand.traceWanted(StreamId.random(), null));
        assertFalse(predicateWasCalled.get(), "predicate must not be consulted for a null assetId");
    }

    @Test
    void touchedStreamCountsAsDemandedEvenWithNoSseWatcher() {
        StreamId streamId = StreamId.random();
        LiveAndPollTraceDemand demand = new LiveAndPollTraceDemand(assetId -> false, POLL_TTL);

        demand.touched(streamId);

        assertTrue(demand.traceWanted(streamId, AssetId.random()));
    }

    @Test
    void touchingOneStreamDoesNotDemandAnother() {
        StreamId touchedStream = StreamId.random();
        StreamId untouchedStream = StreamId.random();
        LiveAndPollTraceDemand demand = new LiveAndPollTraceDemand(assetId -> false, POLL_TTL);

        demand.touched(touchedStream);

        assertTrue(demand.traceWanted(touchedStream, null));
        assertFalse(demand.traceWanted(untouchedStream, null));
    }

    @Test
    void aPollJustInsideTheTtlStillCountsAsDemand() {
        StreamId streamId = StreamId.random();
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-08-15T10:00:00Z"));
        LiveAndPollTraceDemand demand = new LiveAndPollTraceDemand(assetId -> false, POLL_TTL, now::get);

        demand.touched(streamId);
        now.set(now.get().plus(POLL_TTL).minusMillis(1));

        assertTrue(demand.traceWanted(streamId, null), "one millisecond inside the TTL still counts");
    }

    @Test
    void aPollExactlyAtTheTtlBoundaryHasExpired() {
        StreamId streamId = StreamId.random();
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-08-15T10:00:00Z"));
        LiveAndPollTraceDemand demand = new LiveAndPollTraceDemand(assetId -> false, POLL_TTL, now::get);

        demand.touched(streamId);
        now.set(now.get().plus(POLL_TTL));

        assertFalse(demand.traceWanted(streamId, null), "the TTL boundary itself has already expired");
    }

    @Test
    void aThrowingPredicateIsSwallowedRatherThanPropagated() {
        LiveAndPollTraceDemand demand = new LiveAndPollTraceDemand(assetId -> {
            throw new IllegalStateException("registry blew up");
        }, POLL_TTL);

        assertDoesNotThrow(() -> demand.traceWanted(StreamId.random(), AssetId.random()));
    }

    @Test
    void aThrowingPredicateFailsClosedSoOneBrokenLookupNeverOverPaysForTrace() {
        // The DIRECTION of the swallow, pinned separately from the fact of it -- and the opposite
        // direction from LiveAndPollDetectionDemand's own equivalent test. See this class's own
        // javadoc: "unknown" must not default to "keep paying the trace tier's cost."
        LiveAndPollTraceDemand demand = new LiveAndPollTraceDemand(assetId -> {
            throw new IllegalStateException("registry blew up");
        }, POLL_TTL);

        assertFalse(demand.traceWanted(StreamId.random(), AssetId.random()),
                "an undeterminable lookup must fail closed, not over-answer yes for the expensive tier");
    }
}
