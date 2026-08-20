package com.drones.vision.api.live;

import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.StreamId;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every case here is about the same question the class exists to answer honestly: is somebody
 * watching? A wrong "no" stops an operator's video (docs/plans/active/STREAM-STATE-PLAN.md §3.2).
 */
class LiveHlsAndReaderVideoDemandTest {

    private static final Duration TTL = Duration.ofSeconds(30);

    private final StreamId streamId = StreamId.random();
    private final AssetId assetId = AssetId.random();
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-08-19T12:00:00Z"));

    private LiveHlsAndReaderVideoDemand demand(Predicate<AssetId> watchingAsset, Predicate<StreamId> hasReaders) {
        return new LiveHlsAndReaderVideoDemand(watchingAsset, TTL, hasReaders, now::get);
    }

    private static Predicate<AssetId> noneWatching() {
        return asset -> false;
    }

    private static Predicate<StreamId> noReaders() {
        return stream -> false;
    }

    @Test
    void anOpenCockpitOnTheAssetIsDemand() {
        LiveHlsAndReaderVideoDemand demand = demand(assetId::equals, noReaders());

        assertTrue(demand.videoWanted(streamId, assetId));
    }

    @Test
    void aRecentHlsFetchIsDemandUntilTheTtlExpires() {
        LiveHlsAndReaderVideoDemand demand = demand(noneWatching(), noReaders());

        demand.touched(streamId);
        assertTrue(demand.videoWanted(streamId, null));

        now.set(now.get().plus(TTL).minusSeconds(1));
        assertTrue(demand.videoWanted(streamId, null));

        now.set(now.get().plusSeconds(1));
        assertFalse(demand.videoWanted(streamId, null));
    }

    @Test
    void aMediamtxReaderIsDemandEvenWithNoSseAndNoProxyTraffic() {
        // The WHEP case: a WebRTC viewer talks only to mediamtx, so both in-JVM terms are blind.
        LiveHlsAndReaderVideoDemand demand = demand(noneWatching(), streamId::equals);

        assertTrue(demand.videoWanted(streamId, assetId));
    }

    @Test
    void nothingWatchingIsTheOneAnswerThatStopsAStream() {
        LiveHlsAndReaderVideoDemand demand = demand(noneWatching(), noReaders());

        assertFalse(demand.videoWanted(streamId, assetId));
        assertFalse(demand.videoWanted(streamId, null));
    }

    @Test
    void anUnreachableMediamtxFailsOpen() {
        LiveHlsAndReaderVideoDemand demand = demand(noneWatching(), stream -> {
            throw new IllegalStateException("mediamtx Control API unreachable");
        });

        assertTrue(demand.videoWanted(streamId, assetId), "'could not ask' must never read as 'nobody is watching'");
    }

    @Test
    void aThrowingRegistryLookupAlsoFailsOpen() {
        LiveHlsAndReaderVideoDemand demand = demand(asset -> {
            throw new IllegalStateException("registry blew up");
        }, noReaders());

        assertTrue(demand.videoWanted(streamId, assetId));
    }

    @Test
    void theInMemoryTermsShortCircuitTheNetworkOne() {
        AtomicInteger probeCalls = new AtomicInteger();
        LiveHlsAndReaderVideoDemand demand = demand(assetId::equals, stream -> {
            probeCalls.incrementAndGet();
            return false;
        });

        demand.videoWanted(streamId, assetId);

        assertEquals(0, probeCalls.get(), "an open cockpit already answers; mediamtx must not be asked");
    }

    @Test
    void aNullAssetIsNotAFailureAndLetsTheOtherTermsAnswer() {
        LiveHlsAndReaderVideoDemand demand = demand(asset -> true, noReaders());

        // A device-only stream with no owning asset: the SSE term simply has nothing to check.
        assertFalse(demand.videoWanted(streamId, null));
    }
}
