package com.drones.vision.map.application.track;

import com.drones.vision.identity.domain.model.Role;
import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.BoundingBox;
import com.drones.vision.kernel.FixedCameraGeoSettings;
import com.drones.vision.kernel.GeoPosition;
import com.drones.vision.kernel.GroupId;
import com.drones.vision.kernel.Ownership;
import com.drones.vision.kernel.UserId;
import com.drones.vision.map.application.LayerResolver;
import com.drones.vision.map.application.MapAccessPolicy;
import com.drones.vision.map.application.MapAccessPolicy.Viewer;
import com.drones.vision.map.domain.model.CameraPose;
import com.drones.vision.map.domain.model.CameraPoseSource;
import com.drones.vision.map.domain.model.LayerId;
import com.drones.vision.map.domain.model.LayerKind;
import com.drones.vision.map.domain.model.MapEvent;
import com.drones.vision.map.domain.model.MapLayer;
import com.drones.vision.map.domain.model.TrackPoint;
import com.drones.vision.map.domain.port.MapLayerRepositoryPort;
import com.drones.vision.map.domain.port.MapLiveUpdatePort;
import com.drones.vision.map.domain.port.TrackTrailRepositoryPort;
import com.drones.vision.perception.domain.model.Detection;
import com.drones.vision.perception.domain.model.ModelRef;
import com.drones.vision.perception.domain.model.TrackedObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultTrackProjectionServiceTest {

    private static final GeoPosition CAMERA_POSITION = new GeoPosition(50.45, 30.52, null);
    private static final double AGL_METERS = 20.0;
    private static final double YAW_DEGREES = 0.0;
    private static final double PITCH_DEGREES = 10.0;
    private static final double HFOV_DEGREES = 60.0;
    private static final int IMAGE_WIDTH_PIXELS = 1280;
    private static final int IMAGE_HEIGHT_PIXELS = 720;

    /** Bottom-center box: projects to a valid in-view ground fix under the pose above. */
    private static final BoundingBox IN_VIEW_BOX = new BoundingBox(0.45, 0.55, 0.1, 0.1);

    /** Ground-contact near the top of the frame: depression falls below the horizon guard, so this
     * always refuses (D6). */
    private static final BoundingBox BELOW_HORIZON_BOX = new BoundingBox(0.45, 0.0, 0.1, 0.02);

    private static final Instant T0 = Instant.parse("2024-01-01T00:00:00Z");
    private static final Instant T1 = T0.plusSeconds(10);

    private FakeTrackTrailRepositoryPort trailRepository;
    private FakeMapLiveUpdatePort liveUpdatePublisher;
    private FakeMapLayerRepositoryPort mapLayerRepository;
    private LayerResolver layerResolver;
    private TrackProjectionService service;

    private final AssetId assetId = AssetId.random();

    @BeforeEach
    void setUp() {
        trailRepository = new FakeTrackTrailRepositoryPort();
        liveUpdatePublisher = new FakeMapLiveUpdatePort();
        mapLayerRepository = new FakeMapLayerRepositoryPort();
        layerResolver = new LayerResolver(mapLayerRepository, liveUpdatePublisher);
        // Pre-bootstrap the COP layer so its own MapEvent.EntityType#LAYER CREATED event doesn't
        // interleave with the TRACK events under test below (a null targetLayerId lazily bootstraps
        // it on first use otherwise).
        layerResolver.copLayerId();
        liveUpdatePublisher.reset();
        service = newService(0.0);
    }

    private TrackProjectionService newService(double trailMinDistanceMeters) {
        FixedCameraGeoSettings geoSettings = new FixedCameraGeoSettings(5.0, 1000.0, 1.0, 50.0);
        TrackProjectionSettings settings = new TrackProjectionSettings(geoSettings, trailMinDistanceMeters, 1000);
        return new DefaultTrackProjectionService(trailRepository, liveUpdatePublisher, new MapAccessPolicy(),
                layerResolver, settings);
    }

    private static CameraPose pose(AssetId owner, LayerId targetLayerId) {
        return new CameraPose(owner, CAMERA_POSITION, AGL_METERS, YAW_DEGREES, PITCH_DEGREES, HFOV_DEGREES,
                targetLayerId, CameraPoseSource.MANUAL, null, Instant.now(), UserId.random());
    }

    private static TrackedObject trackedObject(long trackId, BoundingBox box, Instant seenAt) {
        Detection detection = new Detection("car", 0.9, box, new ModelRef("yolo", "v1"));
        return new TrackedObject(trackId, detection, seenAt, seenAt);
    }

    private static Viewer adminViewer() {
        return new Viewer(UserId.random(), Set.of(), Role.ADMIN);
    }

    // --- D6 honesty: a refused ray publishes nothing and leaves prior state untouched -----------

    @Test
    void belowHorizonTrackPublishesNothingAndLeavesLiveStateUntouched() {
        CameraPose pose = pose(assetId, null);
        service.project(new TrackProjectionInput(pose, IMAGE_WIDTH_PIXELS, IMAGE_HEIGHT_PIXELS,
                List.of(trackedObject(1L, IN_VIEW_BOX, T0)), T0));

        assertEquals(1, liveUpdatePublisher.events.size());
        assertEquals(1, trailRepository.points.size());
        ProjectedTrackView before = onlyView(service.list(adminViewer()));

        liveUpdatePublisher.reset();
        service.project(new TrackProjectionInput(pose, IMAGE_WIDTH_PIXELS, IMAGE_HEIGHT_PIXELS,
                List.of(trackedObject(1L, BELOW_HORIZON_BOX, T1)), T1));

        assertTrue(liveUpdatePublisher.events.isEmpty(), "a refused ray must publish nothing");
        assertEquals(1, trailRepository.points.size(), "a refused ray must not append a trail point");

        ProjectedTrackView after = onlyView(service.list(adminViewer()));
        assertEquals(before.track(), after.track(), "prior live state must be left exactly as it was");
    }

    private static ProjectedTrackView onlyView(List<ProjectedTrackView> views) {
        assertEquals(1, views.size());
        return views.get(0);
    }

    // --- Expiry: a track dropping out of a later project() call's list clears it -----------------

    @Test
    void trackDroppingFromTracksListPublishesClearedAndIsRemoved() {
        CameraPose pose = pose(assetId, null);
        service.project(new TrackProjectionInput(pose, IMAGE_WIDTH_PIXELS, IMAGE_HEIGHT_PIXELS,
                List.of(trackedObject(1L, IN_VIEW_BOX, T0)), T0));
        assertEquals(1, service.list(adminViewer()).size());
        liveUpdatePublisher.reset();

        service.project(new TrackProjectionInput(pose, IMAGE_WIDTH_PIXELS, IMAGE_HEIGHT_PIXELS, List.of(), T1));

        assertTrue(service.list(adminViewer()).isEmpty());
        assertEquals(1, liveUpdatePublisher.events.size());
        MapEvent event = liveUpdatePublisher.events.get(0);
        assertEquals(MapEvent.EntityType.TRACK, event.entity());
        assertEquals(MapEvent.Action.CLEARED, event.action());
    }

    // --- clearAsset: publishes CLEARED for every live track of one asset, others untouched -------

    @Test
    void clearAssetPublishesClearedForEveryLiveTrackOfThatAssetOnly() {
        CameraPose ownedPose = pose(assetId, null);
        AssetId otherAssetId = AssetId.random();
        CameraPose otherPose = pose(otherAssetId, null);

        service.project(new TrackProjectionInput(ownedPose, IMAGE_WIDTH_PIXELS, IMAGE_HEIGHT_PIXELS,
                List.of(trackedObject(1L, IN_VIEW_BOX, T0), trackedObject(2L, IN_VIEW_BOX, T0)), T0));
        service.project(new TrackProjectionInput(otherPose, IMAGE_WIDTH_PIXELS, IMAGE_HEIGHT_PIXELS,
                List.of(trackedObject(1L, IN_VIEW_BOX, T0)), T0));
        assertEquals(3, service.list(adminViewer()).size());
        liveUpdatePublisher.reset();

        service.clearAsset(assetId);

        List<ProjectedTrackView> remaining = service.list(adminViewer());
        assertEquals(1, remaining.size());
        assertEquals(otherAssetId, remaining.get(0).track().assetId());

        assertEquals(2, liveUpdatePublisher.events.size());
        assertTrue(liveUpdatePublisher.events.stream().allMatch(e -> e.action() == MapEvent.Action.CLEARED));
    }

    @Test
    void clearAssetIsANoOpForAnAssetWithNoLiveTracks() {
        service.clearAsset(assetId);

        assertTrue(liveUpdatePublisher.events.isEmpty());
    }

    // --- D7 decimation: no trail point stored under trailMinDistanceMeters -----------------------

    @Test
    void decimationStoresNoTrailPointUnderMinDistance() {
        TrackProjectionService decimatingService = newService(1_000_000.0);
        CameraPose pose = pose(assetId, null);

        decimatingService.project(new TrackProjectionInput(pose, IMAGE_WIDTH_PIXELS, IMAGE_HEIGHT_PIXELS,
                List.of(trackedObject(1L, IN_VIEW_BOX, T0)), T0));
        assertEquals(1, trailRepository.points.size());

        // A slightly different box still projects a valid (if slightly different) ground fix, but
        // nowhere near the 1,000km decimation threshold configured above.
        BoundingBox slightlyMoved = new BoundingBox(0.46, 0.55, 0.1, 0.1);
        decimatingService.project(new TrackProjectionInput(pose, IMAGE_WIDTH_PIXELS, IMAGE_HEIGHT_PIXELS,
                List.of(trackedObject(1L, slightlyMoved, T1)), T1));

        assertEquals(1, trailRepository.points.size(), "a fix under the decimation threshold must not be stored");
    }

    // --- pruneTrail delegates to the port ------------------------------------------------------

    @Test
    void pruneTrailDelegatesToThePort() {
        Instant cutoff = T0.minusSeconds(3600);

        service.pruneTrail(cutoff);

        assertEquals(cutoff, trailRepository.lastPruneCutoff);
    }

    // --- list(Viewer) respects MapAccessPolicy layer scoping ---------------------------------------

    @Test
    void listRespectsLayerVisibilityScoping() {
        GroupId visibleGroup = GroupId.random();
        GroupId hiddenGroup = GroupId.random();
        MapLayer visibleLayer = mapLayerRepository.save(new MapLayer(LayerId.random(), "Visible team",
                LayerKind.TEAM, new Ownership(UserId.random(), visibleGroup), List.of(), Instant.now()));
        MapLayer hiddenLayer = mapLayerRepository.save(new MapLayer(LayerId.random(), "Hidden team", LayerKind.TEAM,
                new Ownership(UserId.random(), hiddenGroup), List.of(), Instant.now()));

        AssetId visibleAsset = AssetId.random();
        AssetId hiddenAsset = AssetId.random();
        service.project(new TrackProjectionInput(pose(visibleAsset, visibleLayer.id()), IMAGE_WIDTH_PIXELS,
                IMAGE_HEIGHT_PIXELS, List.of(trackedObject(1L, IN_VIEW_BOX, T0)), T0));
        service.project(new TrackProjectionInput(pose(hiddenAsset, hiddenLayer.id()), IMAGE_WIDTH_PIXELS,
                IMAGE_HEIGHT_PIXELS, List.of(trackedObject(1L, IN_VIEW_BOX, T0)), T0));

        Viewer pilotInVisibleGroup = new Viewer(UserId.random(), Set.of(visibleGroup), Role.PILOT);

        List<ProjectedTrackView> visible = service.list(pilotInVisibleGroup);

        assertEquals(1, visible.size());
        assertEquals(visibleAsset, visible.get(0).track().assetId());
    }

    // --- Fakes ---------------------------------------------------------------------------------

    /** In-memory {@link TrackTrailRepositoryPort}. */
    static final class FakeTrackTrailRepositoryPort implements TrackTrailRepositoryPort {
        final List<TrackPoint> points = new ArrayList<>();
        Instant lastPruneCutoff;

        @Override
        public TrackPoint save(TrackPoint point) {
            points.add(point);
            return point;
        }

        @Override
        public List<TrackPoint> findByTrack(AssetId assetId, long trackId) {
            return points.stream().filter(p -> p.assetId().equals(assetId) && p.trackId() == trackId).toList();
        }

        @Override
        public Optional<TrackPoint> findLatest(AssetId assetId, long trackId) {
            List<TrackPoint> forTrack = findByTrack(assetId, trackId);
            return forTrack.isEmpty() ? Optional.empty() : Optional.of(forTrack.get(forTrack.size() - 1));
        }

        @Override
        public void trimToMostRecent(AssetId assetId, long trackId, int maxPoints) {
            List<TrackPoint> forTrack = findByTrack(assetId, trackId);
            if (forTrack.size() <= maxPoints) {
                return;
            }
            points.removeAll(forTrack.subList(0, forTrack.size() - maxPoints));
        }

        @Override
        public void deleteOlderThan(Instant cutoff) {
            lastPruneCutoff = cutoff;
            points.removeIf(p -> p.capturedAt().isBefore(cutoff));
        }
    }

    /** Capturing fake {@link MapLiveUpdatePort}. */
    static final class FakeMapLiveUpdatePort implements MapLiveUpdatePort {
        final List<MapEvent> events = new ArrayList<>();

        void reset() {
            events.clear();
        }

        @Override
        public void publishMapEvent(MapEvent event) {
            events.add(event);
        }
    }

    /** In-memory {@link MapLayerRepositoryPort}. */
    static final class FakeMapLayerRepositoryPort implements MapLayerRepositoryPort {
        private final Map<LayerId, MapLayer> byId = new ConcurrentHashMap<>();

        @Override
        public MapLayer save(MapLayer layer) {
            byId.put(layer.id(), layer);
            return layer;
        }

        @Override
        public Optional<MapLayer> findById(LayerId id) {
            return Optional.ofNullable(byId.get(id));
        }

        @Override
        public List<MapLayer> findAll() {
            return List.copyOf(byId.values());
        }

        @Override
        public void deleteById(LayerId id) {
            byId.remove(id);
        }
    }
}
