package com.drones.vision.perception.application.geo;

import com.drones.vision.perception.domain.model.IngestProgress;
import com.drones.vision.perception.domain.model.IngestState;
import com.drones.vision.perception.domain.model.ReferenceIndexSummary;
import com.drones.vision.perception.domain.model.ReferenceRegion;
import com.drones.vision.perception.domain.model.RegionBounds;
import com.drones.vision.perception.domain.model.RegionIngestSpec;
import com.drones.vision.perception.domain.model.RegionStatus;
import com.drones.vision.perception.domain.model.Tile;
import com.drones.vision.perception.domain.model.TileCoordinate;
import com.drones.vision.perception.domain.model.TileGrid;
import com.drones.vision.perception.domain.port.ReferenceIndexPort;
import com.drones.vision.perception.domain.port.ReferenceTileSourcePort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Flow;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ReferenceTileSourcePort}/{@link ReferenceIndexPort} are hand-rolled in-memory fakes (this
 * module's dominant style, e.g. {@code DefaultTrackCorrectionServiceTest} in {@code
 * vision-flight} — no cross-context service is a dependency here, so nothing is a Mockito mock).
 */
class DefaultReferenceRegionServiceTest {

    // A z17 bbox that covers exactly 4 tiles: x in [76685,76686], y in [44227,44228].
    private static final RegionBounds SMALL_BOUNDS =
            new RegionBounds(50.39556261, 50.39346148, 30.62603760, 30.62274170);
    // The plan's own Poznyaky bbox -- 110 tiles at z17.
    private static final RegionBounds LARGE_BOUNDS =
            new RegionBounds(50.4020, 50.3860, 30.6400, 30.6120);

    private FakeReferenceTileSourcePort tileSourcePort;
    private FakeReferenceIndexPort indexPort;

    @BeforeEach
    void setUp() {
        tileSourcePort = new FakeReferenceTileSourcePort();
        indexPort = new FakeReferenceIndexPort();
    }

    private DefaultReferenceRegionService service(int maxTiles) {
        return new DefaultReferenceRegionService(tileSourcePort, indexPort, new ReferenceRegionSettings(maxTiles));
    }

    @Test
    void ingestReturnsBuildingImmediatelyAndFetchesEveryTile() {
        DefaultReferenceRegionService service = service(1000);
        RegionIngestSpec spec = new RegionIngestSpec("kyiv-pozniaky", "Poznyaky", SMALL_BOUNDS, 17);

        ReferenceRegion region = service.ingest(spec);

        assertEquals("kyiv-pozniaky", region.regionId());
        assertEquals(RegionStatus.BUILDING, region.status());
        assertNull(region.summary());

        List<TileCoordinate> expected = TileGrid.cover(SMALL_BOUNDS, 17);
        assertEquals(4, expected.size());
        assertEquals(Set.copyOf(expected), Set.copyOf(tileSourcePort.fetched));
        assertEquals(Set.copyOf(expected),
                indexPort.builtWith.get("kyiv-pozniaky").stream().map(Tile::coordinate).collect(Collectors.toSet()));
    }

    @Test
    void ingestRefusesWhenTileCountExceedsCeilingNamingTheCount() {
        DefaultReferenceRegionService service = service(50);
        RegionIngestSpec spec = new RegionIngestSpec("kyiv-pozniaky", "Poznyaky", LARGE_BOUNDS, 17);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> service.ingest(spec));

        assertTrue(e.getMessage().contains("110"), "message should name the tile count: " + e.getMessage());
        assertTrue(tileSourcePort.fetched.isEmpty(), "no tile should be fetched once the ceiling refuses the ingest");
        assertTrue(indexPort.builtWith.isEmpty());
    }

    @Test
    void ingestThrowsWhenNoTileProviderConfigured() {
        tileSourcePort.supports = false;
        DefaultReferenceRegionService service = service(1000);
        RegionIngestSpec spec = new RegionIngestSpec("kyiv-pozniaky", "Poznyaky", SMALL_BOUNDS, 17);

        assertThrows(IllegalStateException.class, () -> service.ingest(spec));
        assertTrue(indexPort.builtWith.isEmpty());
    }

    @Test
    void progressTracksAnInFlightJobThroughToSuccess() {
        DefaultReferenceRegionService service = service(1000);
        RegionIngestSpec spec = new RegionIngestSpec("kyiv-pozniaky", "Poznyaky", SMALL_BOUNDS, 17);
        service.ingest(spec);

        Optional<IngestProgress> initial = service.progress("kyiv-pozniaky");
        assertTrue(initial.isPresent());
        assertEquals(IngestState.RUNNING, initial.get().state());
        assertEquals(4, initial.get().total());

        ControllableProgressPublisher publisher = indexPort.publisherFor("kyiv-pozniaky");
        publisher.emit(new IngestProgress("kyiv-pozniaky", "encoding", 2, 4, IngestState.RUNNING, "", null));
        assertEquals("encoding", service.progress("kyiv-pozniaky").orElseThrow().phase());

        ReferenceIndexSummary summary = summary("kyiv-pozniaky", 4, 0);
        publisher.emit(new IngestProgress("kyiv-pozniaky", "done", 4, 4, IngestState.SUCCEEDED, "", summary));
        publisher.complete();

        // The job leaves in-memory tracking on SUCCEEDED -- cv-service's own ListRegions is now the
        // source of truth, and nothing in this fake has told it about the new region yet.
        assertTrue(service.progress("kyiv-pozniaky").isEmpty());
        assertTrue(service.list().isEmpty());

        // Once cv-service (the fake) reports it built, both reads pick it up from there instead.
        indexPort.summaries.add(summary);
        assertEquals(RegionStatus.READY, service.list().get(0).status());
        IngestProgress synthesized = service.progress("kyiv-pozniaky").orElseThrow();
        assertEquals(IngestState.SUCCEEDED, synthesized.state());
        assertEquals(summary, synthesized.summary());
    }

    @Test
    void ingestFailurePersistsAsFailedUntilDeleted() {
        DefaultReferenceRegionService service = service(1000);
        RegionIngestSpec spec = new RegionIngestSpec("kyiv-pozniaky", "Poznyaky", SMALL_BOUNDS, 17);
        service.ingest(spec);

        ControllableProgressPublisher publisher = indexPort.publisherFor("kyiv-pozniaky");
        publisher.fail(new RuntimeException("provider quota exhausted"));

        IngestProgress progress = service.progress("kyiv-pozniaky").orElseThrow();
        assertEquals(IngestState.FAILED, progress.state());
        assertEquals("provider quota exhausted", progress.message());

        List<ReferenceRegion> listed = service.list();
        assertEquals(1, listed.size());
        assertEquals(RegionStatus.FAILED, listed.get(0).status());

        service.delete("kyiv-pozniaky");
        assertEquals(List.of("kyiv-pozniaky"), indexPort.deleted);
        assertTrue(service.progress("kyiv-pozniaky").isEmpty());
        assertTrue(service.list().isEmpty());
    }

    @Test
    void listMergesBuiltAndInFlightRegionsAndSurfacesNeverAcceptDistinctFromReady() {
        DefaultReferenceRegionService service = service(1000);
        indexPort.summaries.add(summary("already-built", 100, 0)); // READY
        indexPort.summaries.add(summary("dead-zone", 40, 40));      // every cell never-accept -> NEVER_ACCEPT
        service.ingest(new RegionIngestSpec("in-progress", "In progress", SMALL_BOUNDS, 17));

        Map<String, RegionStatus> byId =
                service.list().stream().collect(Collectors.toMap(ReferenceRegion::regionId, ReferenceRegion::status));

        assertEquals(RegionStatus.READY, byId.get("already-built"));
        assertEquals(RegionStatus.NEVER_ACCEPT, byId.get("dead-zone"));
        assertEquals(RegionStatus.BUILDING, byId.get("in-progress"));
    }

    @Test
    void deleteIsIdempotentForAnUnknownRegion() {
        DefaultReferenceRegionService service = service(1000);

        service.delete("never-ingested");

        assertEquals(List.of("never-ingested"), indexPort.deleted);
    }

    @Test
    void progressIsEmptyForAnUnknownRegion() {
        DefaultReferenceRegionService service = service(1000);

        assertFalse(service.progress("unknown").isPresent());
    }

    private static ReferenceIndexSummary summary(String regionId, int tileCount, int neverAcceptCells) {
        return new ReferenceIndexSummary(regionId, regionId, SMALL_BOUNDS, 17,
                Instant.parse("2026-08-20T09:12:03Z"), tileCount, tileCount, 512, "eigenplaces_r18_512", 0.62, 0.03,
                0.55, 43.0, 12_000_000L, neverAcceptCells);
    }

    /** Lets a test drive a build's progress stream by hand, exactly like production's async job. */
    private static final class ControllableProgressPublisher implements Flow.Publisher<IngestProgress> {
        private Flow.Subscriber<? super IngestProgress> subscriber;

        @Override
        public void subscribe(Flow.Subscriber<? super IngestProgress> subscriber) {
            this.subscriber = subscriber;
            subscriber.onSubscribe(new Flow.Subscription() {
                @Override
                public void request(long n) {
                    // the production subscriber always requests unbounded; nothing to honor here
                }

                @Override
                public void cancel() {
                    // no-op
                }
            });
        }

        void emit(IngestProgress progress) {
            subscriber.onNext(progress);
        }

        void fail(Throwable throwable) {
            subscriber.onError(throwable);
        }

        void complete() {
            subscriber.onComplete();
        }
    }

    private static final class FakeReferenceTileSourcePort implements ReferenceTileSourcePort {
        private boolean supports = true;
        private final List<TileCoordinate> fetched = new ArrayList<>();

        @Override
        public boolean supports() {
            return supports;
        }

        @Override
        public byte[] fetch(int z, int x, int y) {
            TileCoordinate coordinate = new TileCoordinate(z, x, y);
            fetched.add(coordinate);
            return new byte[]{(byte) z, (byte) x, (byte) y};
        }
    }

    private static final class FakeReferenceIndexPort implements ReferenceIndexPort {
        private final Map<String, List<Tile>> builtWith = new HashMap<>();
        private final Map<String, ControllableProgressPublisher> publishers = new HashMap<>();
        private final List<ReferenceIndexSummary> summaries = new ArrayList<>();
        private final List<String> deleted = new ArrayList<>();

        @Override
        public Flow.Publisher<IngestProgress> build(RegionIngestSpec spec, Iterable<Tile> tiles) {
            List<Tile> materialized = new ArrayList<>();
            tiles.forEach(materialized::add);
            builtWith.put(spec.regionId(), materialized);
            ControllableProgressPublisher publisher = new ControllableProgressPublisher();
            publishers.put(spec.regionId(), publisher);
            return publisher;
        }

        @Override
        public List<ReferenceIndexSummary> list() {
            return List.copyOf(summaries);
        }

        @Override
        public void delete(String regionId) {
            deleted.add(regionId);
            summaries.removeIf(summary -> summary.regionId().equals(regionId));
        }

        ControllableProgressPublisher publisherFor(String regionId) {
            return publishers.get(regionId);
        }
    }
}
