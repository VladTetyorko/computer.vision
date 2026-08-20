package com.drones.vision.perception.application.geo;

import com.drones.vision.perception.domain.model.IngestProgress;
import com.drones.vision.perception.domain.model.IngestState;
import com.drones.vision.perception.domain.model.ReferenceIndexSummary;
import com.drones.vision.perception.domain.model.ReferenceRegion;
import com.drones.vision.perception.domain.model.RegionIngestSpec;
import com.drones.vision.perception.domain.model.RegionStatus;
import com.drones.vision.perception.domain.model.Tile;
import com.drones.vision.perception.domain.model.TileCoordinate;
import com.drones.vision.perception.domain.model.TileGrid;
import com.drones.vision.perception.domain.port.ReferenceIndexPort;
import com.drones.vision.perception.domain.port.ReferenceTileSourcePort;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * {@link ReferenceRegionService} backed by {@link ReferenceTileSourcePort} (fetching tiles) and
 * {@link ReferenceIndexPort} (cv-service's {@code Geolocation} ingest RPCs). Holds nothing durable —
 * D10's "cv-service is the single source of truth for what regions exist": {@link #inFlightJobs}
 * tracks only jobs still running (or lastly failed) in this JVM's own memory, lost on restart by
 * design.
 */
public final class DefaultReferenceRegionService implements ReferenceRegionService {

    private static final System.Logger LOG = System.getLogger(DefaultReferenceRegionService.class.getName());

    private final ReferenceTileSourcePort tileSourcePort;
    private final ReferenceIndexPort indexPort;
    private final ReferenceRegionSettings settings;

    private final Map<String, IngestJob> inFlightJobs = new ConcurrentHashMap<>();

    public DefaultReferenceRegionService(ReferenceTileSourcePort tileSourcePort, ReferenceIndexPort indexPort,
                                          ReferenceRegionSettings settings) {
        this.tileSourcePort = Objects.requireNonNull(tileSourcePort, "tileSourcePort must not be null");
        this.indexPort = Objects.requireNonNull(indexPort, "indexPort must not be null");
        this.settings = Objects.requireNonNull(settings, "settings must not be null");
    }

    @Override
    public ReferenceRegion ingest(RegionIngestSpec spec) {
        Objects.requireNonNull(spec, "spec must not be null");
        long tileCount = TileGrid.count(spec.bounds(), spec.zoom());
        if (tileCount > settings.maxTiles()) {
            throw new IllegalArgumentException(
                    "region '" + spec.regionId() + "' would need " + tileCount
                            + " tiles, over the configured ceiling of " + settings.maxTiles());
        }
        if (!tileSourcePort.supports()) {
            throw new IllegalStateException("no reference tile provider is configured");
        }

        List<TileCoordinate> coordinates = TileGrid.cover(spec.bounds(), spec.zoom());
        Iterable<Tile> tiles = () -> coordinates.stream()
                .map(coordinate -> new Tile(coordinate,
                        tileSourcePort.fetch(coordinate.z(), coordinate.x(), coordinate.y())))
                .iterator();

        IngestJob job = new IngestJob(spec, (int) tileCount);
        inFlightJobs.put(spec.regionId(), job);
        indexPort.build(spec, tiles).subscribe(new ProgressSubscriber(job));
        return job.toRegion();
    }

    @Override
    public List<ReferenceRegion> list() {
        List<ReferenceIndexSummary> built = indexPort.list();
        Set<String> builtIds = built.stream().map(ReferenceIndexSummary::regionId).collect(Collectors.toSet());

        List<ReferenceRegion> result = new ArrayList<>();
        for (ReferenceIndexSummary summary : built) {
            result.add(new ReferenceRegion(summary.regionId(), summary.name(), summary.bounds(), summary.zoom(),
                    summary.impliedStatus(), summary));
        }
        for (IngestJob job : inFlightJobs.values()) {
            if (!builtIds.contains(job.spec.regionId())) {
                result.add(job.toRegion());
            }
        }
        return List.copyOf(result);
    }

    @Override
    public Optional<IngestProgress> progress(String regionId) {
        Objects.requireNonNull(regionId, "regionId must not be null");
        IngestJob job = inFlightJobs.get(regionId);
        if (job != null) {
            return Optional.of(job.latest.get());
        }
        return indexPort.list().stream()
                .filter(summary -> summary.regionId().equals(regionId))
                .findFirst()
                .map(summary -> new IngestProgress(regionId, "done", 0, 0, IngestState.SUCCEEDED, "", summary));
    }

    @Override
    public void delete(String regionId) {
        Objects.requireNonNull(regionId, "regionId must not be null");
        indexPort.delete(regionId);
        inFlightJobs.remove(regionId);
    }

    /** One in-flight (or lastly-failed) ingest job — tracked purely in memory, never persisted (D10). */
    private static final class IngestJob {
        private final RegionIngestSpec spec;
        private final AtomicReference<IngestProgress> latest;

        IngestJob(RegionIngestSpec spec, int tileCount) {
            this.spec = spec;
            this.latest = new AtomicReference<>(
                    new IngestProgress(spec.regionId(), "receiving", 0, tileCount, IngestState.RUNNING, "", null));
        }

        ReferenceRegion toRegion() {
            RegionStatus status =
                    latest.get().state() == IngestState.FAILED ? RegionStatus.FAILED : RegionStatus.BUILDING;
            return new ReferenceRegion(spec.regionId(), spec.name(), spec.bounds(), spec.zoom(), status, null);
        }
    }

    /**
     * Folds one build's progress stream into its job's latest-known state, and drops the job from
     * {@link #inFlightJobs} the moment it succeeds — from then on {@link ReferenceIndexPort#list} is
     * the region's source of truth, and holding onto a stale in-memory copy would only risk it
     * disagreeing with cv-service later. A failed job is deliberately left in place so an operator
     * can see the failure (and its message) until they delete or re-ingest the region.
     */
    private final class ProgressSubscriber implements Flow.Subscriber<IngestProgress> {
        private final IngestJob job;

        ProgressSubscriber(IngestJob job) {
            this.job = job;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(IngestProgress item) {
            job.latest.set(item);
            if (item.state() == IngestState.SUCCEEDED) {
                inFlightJobs.remove(job.spec.regionId(), job);
            }
        }

        @Override
        public void onError(Throwable throwable) {
            LOG.log(System.Logger.Level.WARNING, () -> "region ingest failed for " + job.spec.regionId(), throwable);
            IngestProgress last = job.latest.get();
            String message =
                    throwable.getMessage() == null ? throwable.getClass().getSimpleName() : throwable.getMessage();
            job.latest.set(new IngestProgress(job.spec.regionId(), last.phase(), last.done(), last.total(),
                    IngestState.FAILED, message, null));
        }

        @Override
        public void onComplete() {
            // The terminal state (SUCCEEDED/FAILED) was already stamped by the last onNext/onError;
            // completion itself carries no further information.
        }
    }
}
