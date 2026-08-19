package com.drones.vision.map.application.track;

import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.FixedCameraGeo;
import com.drones.vision.kernel.FixedCameraPose;
import com.drones.vision.kernel.GeoProjection;
import com.drones.vision.kernel.GroundFix;
import com.drones.vision.map.application.LayerResolver;
import com.drones.vision.map.application.MapAccessPolicy;
import com.drones.vision.map.application.MapAccessPolicy.Viewer;
import com.drones.vision.map.domain.model.CameraPose;
import com.drones.vision.map.domain.model.LayerId;
import com.drones.vision.map.domain.model.MapEvent;
import com.drones.vision.map.domain.model.MapEvent.Action;
import com.drones.vision.map.domain.model.MapEvent.EntityType;
import com.drones.vision.map.domain.model.MapLayer;
import com.drones.vision.map.domain.model.ProjectedTrack;
import com.drones.vision.map.domain.model.TrackPoint;
import com.drones.vision.map.domain.port.MapLiveUpdatePort;
import com.drones.vision.map.domain.port.TrackTrailRepositoryPort;
import com.drones.vision.perception.domain.model.TrackedObject;

import java.time.Instant;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * The one implementation of {@link TrackProjectionService}.
 *
 * <h2>Threading</h2>
 * The live picture is a {@link ConcurrentHashMap} keyed by {@link TrackKey}; {@link #project} (the
 * runner's scheduled thread) and {@link #list} (concurrent HTTP reads) both operate on it safely.
 * {@link TrackTrailRepositoryPort} is the only other mutable state, and it is the port
 * implementation's own responsibility to be concurrency-safe (documented on the port).
 */
public final class DefaultTrackProjectionService implements TrackProjectionService {

    private final TrackTrailRepositoryPort trailRepository;
    private final MapLiveUpdatePort liveUpdatePublisher;
    private final MapAccessPolicy policy;
    private final LayerResolver layerResolver;
    private final TrackProjectionSettings settings;

    private final Map<TrackKey, ProjectedTrack> liveTracks = new ConcurrentHashMap<>();

    public DefaultTrackProjectionService(TrackTrailRepositoryPort trailRepository, MapLiveUpdatePort liveUpdatePublisher,
                                          MapAccessPolicy policy, LayerResolver layerResolver,
                                          TrackProjectionSettings settings) {
        this.trailRepository = Objects.requireNonNull(trailRepository, "trailRepository must not be null");
        this.liveUpdatePublisher = Objects.requireNonNull(liveUpdatePublisher, "liveUpdatePublisher must not be null");
        this.policy = Objects.requireNonNull(policy, "policy must not be null");
        this.layerResolver = Objects.requireNonNull(layerResolver, "layerResolver must not be null");
        this.settings = Objects.requireNonNull(settings, "settings must not be null");
    }

    @Override
    public void project(TrackProjectionInput input) {
        Objects.requireNonNull(input, "input must not be null");

        CameraPose pose = input.pose();
        AssetId assetId = pose.assetId();
        LayerId layerId = pose.targetLayerId() != null ? pose.targetLayerId() : layerResolver.copLayerId();
        FixedCameraPose fixedPose = pose.toFixedCameraPose();

        Set<Long> seenTrackIds = new HashSet<>();
        for (TrackedObject tracked : input.tracks()) {
            seenTrackIds.add(tracked.trackId());
            Optional<GroundFix> fix = FixedCameraGeo.project(fixedPose, input.imageWidthPixels(),
                    input.imageHeightPixels(), tracked.detection().box(), settings.geoSettings());
            // D6: a refused ray publishes nothing this tick, and the previously-held live state (if
            // any) is left untouched -- an unprojectable frame does not mean the object left the
            // track book, so it must not be cleared either.
            fix.ifPresent(groundFix -> applyFix(assetId, tracked, layerId, groundFix, input.observedAt()));
        }

        expireDropped(assetId, seenTrackIds);
    }

    private void applyFix(AssetId assetId, TrackedObject tracked, LayerId layerId, GroundFix fix, Instant now) {
        long trackId = tracked.trackId();
        String label = tracked.detection().label();

        maybeAppendTrailPoint(assetId, trackId, label, layerId, fix, now);

        ProjectedTrack updated = new ProjectedTrack(assetId, trackId, label, layerId, fix.position(),
                fix.rangeMeters(), fix.errorRadiusMeters(), now);
        ProjectedTrack previous = liveTracks.put(new TrackKey(assetId, trackId), updated);
        Action action = previous == null ? Action.CREATED : Action.UPDATED;
        liveUpdatePublisher.publishMapEvent(new MapEvent(EntityType.TRACK, action, layerId, updated));
    }

    /** D7 decimation: a trail point is stored only once the track has moved far enough from the last one. */
    private void maybeAppendTrailPoint(AssetId assetId, long trackId, String label, LayerId layerId, GroundFix fix,
                                        Instant now) {
        Optional<TrackPoint> latest = trailRepository.findLatest(assetId, trackId);
        boolean farEnough = latest.isEmpty() || GeoProjection.bearingDistance(latest.get().position(), fix.position())
                .distanceMeters() >= settings.trailMinDistanceMeters();
        if (!farEnough) {
            return;
        }
        trailRepository.save(new TrackPoint(assetId, trackId, label, layerId, fix.position(), fix.errorRadiusMeters(), now));
        trailRepository.trimToMostRecent(assetId, trackId, settings.trailMaxPointsPerTrack());
    }

    /** Clears every live track for {@code assetId} not present in this tick's {@code seenTrackIds}. */
    private void expireDropped(AssetId assetId, Set<Long> seenTrackIds) {
        Iterator<Map.Entry<TrackKey, ProjectedTrack>> it = liveTracks.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<TrackKey, ProjectedTrack> entry = it.next();
            TrackKey key = entry.getKey();
            if (key.assetId().equals(assetId) && !seenTrackIds.contains(key.trackId())) {
                it.remove();
                publishCleared(entry.getValue());
            }
        }
    }

    @Override
    public void clearAsset(AssetId assetId) {
        Objects.requireNonNull(assetId, "assetId must not be null");
        Iterator<Map.Entry<TrackKey, ProjectedTrack>> it = liveTracks.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<TrackKey, ProjectedTrack> entry = it.next();
            if (entry.getKey().assetId().equals(assetId)) {
                it.remove();
                publishCleared(entry.getValue());
            }
        }
    }

    private void publishCleared(ProjectedTrack track) {
        liveUpdatePublisher.publishMapEvent(new MapEvent(EntityType.TRACK, Action.CLEARED, track.layerId(), track));
    }

    @Override
    public void pruneTrail(Instant cutoff) {
        Objects.requireNonNull(cutoff, "cutoff must not be null");
        trailRepository.deleteOlderThan(cutoff);
    }

    @Override
    public List<ProjectedTrackView> list(Viewer v) {
        Objects.requireNonNull(v, "v must not be null");
        Map<LayerId, MapLayer> layersById =
                layerResolver.findAll().stream().collect(Collectors.toMap(MapLayer::id, layer -> layer));

        return liveTracks.values().stream()
                .filter(track -> isVisible(v, track, layersById))
                .sorted((a, b) -> {
                    int byAsset = a.assetId().value().compareTo(b.assetId().value());
                    return byAsset != 0 ? byAsset : Long.compare(a.trackId(), b.trackId());
                })
                .map(track -> new ProjectedTrackView(track, trailRepository.findByTrack(track.assetId(), track.trackId())))
                .toList();
    }

    private boolean isVisible(Viewer v, ProjectedTrack track, Map<LayerId, MapLayer> layersById) {
        MapLayer layer = layersById.get(track.layerId());
        return layer != null && policy.canView(v, layer);
    }

    /** In-memory live-track key; implementation detail, not part of the public API. */
    private record TrackKey(AssetId assetId, long trackId) {
    }
}
