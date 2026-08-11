package com.drones.vision.application.map;

import com.drones.vision.kernel.AssetId;
import com.drones.vision.events.domain.model.DetectionEvent;
import com.drones.vision.perception.domain.model.DetectionResult;
import com.drones.vision.map.domain.model.DrawKind;
import com.drones.vision.map.domain.model.Drawing;
import com.drones.vision.map.domain.model.DrawingId;
import com.drones.vision.events.domain.model.Event;
import com.drones.vision.kernel.GeoPosition;
import com.drones.vision.kernel.GroupId;
import com.drones.vision.map.domain.model.LayerId;
import com.drones.vision.map.domain.model.LayerKind;
import com.drones.vision.map.domain.model.MapEvent;
import com.drones.vision.map.domain.model.MapLayer;
import com.drones.vision.kernel.Ownership;
import com.drones.vision.identity.domain.model.Role;
import com.drones.vision.flight.domain.model.Telemetry;
import com.drones.vision.kernel.UserId;
import com.drones.vision.map.domain.port.DrawingRepositoryPort;
import com.drones.vision.events.domain.port.LiveUpdatePublisherPort;
import com.drones.vision.map.domain.port.MapLayerRepositoryPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import com.drones.vision.application.map.MapAccessPolicy.Viewer;
import com.drones.vision.application.scope.AccessDeniedException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultDrawingServiceTest {

    private FakeDrawingRepositoryPort drawingRepository;
    private FakeMapLayerRepositoryPort mapLayerRepository;
    private FakeLiveUpdatePublisherPort liveUpdatePublisher;
    private DrawingService service;

    private MapLayer cop;
    private MapLayer team;
    private final GroupId group = GroupId.random();
    private final UserId creator = UserId.random();

    @BeforeEach
    void setUp() {
        drawingRepository = new FakeDrawingRepositoryPort();
        mapLayerRepository = new FakeMapLayerRepositoryPort();
        liveUpdatePublisher = new FakeLiveUpdatePublisherPort();
        LayerResolver layerResolver = new LayerResolver(mapLayerRepository, liveUpdatePublisher);
        service = new DefaultDrawingService(drawingRepository, liveUpdatePublisher, new MapAccessPolicy(),
                layerResolver);

        cop = mapLayerRepository.save(new MapLayer(LayerId.random(), "Common picture", LayerKind.COP,
                new Ownership(UserId.random(), GroupId.random()), List.of(), Instant.now()));
        team = mapLayerRepository.save(new MapLayer(LayerId.random(), "Alpha team", LayerKind.TEAM,
                new Ownership(UserId.random(), group), List.of(), Instant.now()));
        liveUpdatePublisher.events.clear();
    }

    private static Viewer pilot(UserId id, GroupId... groups) {
        return new Viewer(id, Set.of(groups), Role.PILOT);
    }

    private static List<GeoPosition> line() {
        return List.of(new GeoPosition(1, 1, null), new GeoPosition(2, 2, null));
    }

    // --- list ------------------------------------------------------------------

    @Test
    void listReturnsOnlyDrawingsOnVisibleLayers() {
        Drawing onCop = drawingRepository.save(new Drawing(DrawingId.random(), cop.id(), DrawKind.LINE, line(), null,
                null, new Ownership(creator, group), Instant.now()));
        MapLayer otherTeam = mapLayerRepository.save(new MapLayer(LayerId.random(), "Other", LayerKind.TEAM,
                new Ownership(UserId.random(), GroupId.random()), List.of(), Instant.now()));
        drawingRepository.save(new Drawing(DrawingId.random(), otherTeam.id(), DrawKind.LINE, line(), null, null,
                new Ownership(UserId.random(), otherTeam.ownership().groupId()), Instant.now()));

        List<Drawing> visible = service.list(pilot(UserId.random(), group));

        assertEquals(List.of(onCop), visible);
    }

    // --- create ------------------------------------------------------------------

    @Test
    void createOnExplicitLayerPublishesCreated() {
        DrawingSpec spec = new DrawingSpec(team.id(), DrawKind.LINE, line(), "Trench", "accent");

        Drawing created = service.create(pilot(creator, group), spec);

        assertEquals(team.id(), created.layerId());
        assertEquals(creator, created.ownership().ownerId());
        assertEquals("Trench", created.label());
        assertEquals(1, liveUpdatePublisher.events.size());
        assertEquals(MapEvent.Action.CREATED, liveUpdatePublisher.events.get(0).action());
    }

    @Test
    void createWithoutContributeAccessIsDenied() {
        DrawingSpec spec = new DrawingSpec(team.id(), DrawKind.LINE, line(), null, null);

        assertThrows(AccessDeniedException.class, () -> service.create(pilot(UserId.random()), spec));
    }

    @Test
    void createWithNoLayerUsesDefaultLayerResolution() {
        DrawingSpec spec = new DrawingSpec(null, DrawKind.LINE, line(), null, null);

        Drawing created = service.create(pilot(creator, group), spec);

        assertEquals(team.id(), created.layerId());
    }

    @Test
    void createOnUnknownLayerThrowsNoSuchElement() {
        DrawingSpec spec = new DrawingSpec(LayerId.random(), DrawKind.LINE, line(), null, null);

        assertThrows(NoSuchElementException.class, () -> service.create(pilot(creator, group), spec));
    }

    // --- patch/delete: creator or manager ------------------------------------------

    @Test
    void patchByCreatorSucceeds() {
        Drawing existing = drawingRepository.save(new Drawing(DrawingId.random(), team.id(), DrawKind.LINE, line(),
                null, null, new Ownership(creator, group), Instant.now()));
        DrawingPatch patch = new DrawingPatch(Optional.empty(), Optional.of("Renamed"), Optional.empty());

        Drawing updated = service.patch(pilot(creator, group), existing.id(), patch);

        assertEquals("Renamed", updated.label());
        assertEquals(MapEvent.Action.UPDATED, liveUpdatePublisher.events.get(0).action());
    }

    @Test
    void patchByManagerSucceeds() {
        Drawing existing = drawingRepository.save(new Drawing(DrawingId.random(), team.id(), DrawKind.LINE, line(),
                null, null, new Ownership(creator, group), Instant.now()));
        DrawingPatch patch = new DrawingPatch(Optional.empty(), Optional.of("Renamed"), Optional.empty());
        Viewer manager = new Viewer(UserId.random(), Set.of(group), Role.MANAGER);

        Drawing updated = service.patch(manager, existing.id(), patch);

        assertEquals("Renamed", updated.label());
    }

    @Test
    void patchByNonCreatorNonManagerIsDenied() {
        Drawing existing = drawingRepository.save(new Drawing(DrawingId.random(), team.id(), DrawKind.LINE, line(),
                null, null, new Ownership(creator, group), Instant.now()));
        DrawingPatch patch = new DrawingPatch(Optional.empty(), Optional.of("Renamed"), Optional.empty());

        assertThrows(AccessDeniedException.class,
                () -> service.patch(pilot(UserId.random(), group), existing.id(), patch));
    }

    @Test
    void patchAndDeleteOfInvisibleDrawingReadAsUnknown() {
        Drawing existing = drawingRepository.save(new Drawing(DrawingId.random(), team.id(), DrawKind.LINE, line(),
                null, null, new Ownership(creator, group), Instant.now()));

        // An outsider may not learn the drawing exists (docs/plans/done/MAP-REWORK-PLAN.md §4.1) — 404, not 403.
        assertThrows(NoSuchElementException.class,
                () -> service.patch(pilot(UserId.random()), existing.id(), DrawingPatch.NOTHING));
        assertThrows(NoSuchElementException.class, () -> service.delete(pilot(UserId.random()), existing.id()));
        assertTrue(drawingRepository.findById(existing.id()).isPresent());
    }

    @Test
    void patchUpdatesGeometry() {
        Drawing existing = drawingRepository.save(new Drawing(DrawingId.random(), team.id(), DrawKind.LINE, line(),
                null, null, new Ownership(creator, group), Instant.now()));
        List<GeoPosition> newPoints = List.of(new GeoPosition(5, 5, null), new GeoPosition(6, 6, null));
        DrawingPatch patch = new DrawingPatch(Optional.of(newPoints), Optional.empty(), Optional.empty());

        Drawing updated = service.patch(pilot(creator, group), existing.id(), patch);

        assertEquals(newPoints, updated.points());
    }

    @Test
    void patchUnknownIdThrowsNoSuchElement() {
        assertThrows(NoSuchElementException.class,
                () -> service.patch(pilot(creator, group), DrawingId.random(), DrawingPatch.NOTHING));
    }

    @Test
    void deleteByCreatorRemovesAndPublishesDeleted() {
        Drawing existing = drawingRepository.save(new Drawing(DrawingId.random(), team.id(), DrawKind.LINE, line(),
                null, null, new Ownership(creator, group), Instant.now()));

        service.delete(pilot(creator, group), existing.id());

        assertTrue(drawingRepository.findById(existing.id()).isEmpty());
        assertEquals(MapEvent.Action.DELETED, liveUpdatePublisher.events.get(0).action());
    }

    @Test
    void deleteByNonCreatorNonManagerIsDeniedAndKeepsTheDrawing() {
        Drawing existing = drawingRepository.save(new Drawing(DrawingId.random(), team.id(), DrawKind.LINE, line(),
                null, null, new Ownership(creator, group), Instant.now()));

        assertThrows(AccessDeniedException.class,
                () -> service.delete(pilot(UserId.random(), group), existing.id()));
        assertTrue(drawingRepository.findById(existing.id()).isPresent());
    }

    @Test
    void constructorRejectsNullCollaborators() {
        LayerResolver layerResolver = new LayerResolver(mapLayerRepository, liveUpdatePublisher);
        MapAccessPolicy policy = new MapAccessPolicy();
        assertThrows(NullPointerException.class,
                () -> new DefaultDrawingService(null, liveUpdatePublisher, policy, layerResolver));
        assertThrows(NullPointerException.class,
                () -> new DefaultDrawingService(drawingRepository, null, policy, layerResolver));
        assertThrows(NullPointerException.class,
                () -> new DefaultDrawingService(drawingRepository, liveUpdatePublisher, null, layerResolver));
        assertThrows(NullPointerException.class,
                () -> new DefaultDrawingService(drawingRepository, liveUpdatePublisher, policy, null));
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

    /** In-memory {@link DrawingRepositoryPort}. */
    static final class FakeDrawingRepositoryPort implements DrawingRepositoryPort {
        private final Map<DrawingId, Drawing> byId = new ConcurrentHashMap<>();

        @Override
        public Drawing save(Drawing drawing) {
            byId.put(drawing.id(), drawing);
            return drawing;
        }

        @Override
        public Optional<Drawing> findById(DrawingId id) {
            return Optional.ofNullable(byId.get(id));
        }

        @Override
        public List<Drawing> findAll() {
            return List.copyOf(byId.values());
        }

        @Override
        public void deleteById(DrawingId id) {
            byId.remove(id);
        }
    }

    /** Capturing fake {@link LiveUpdatePublisherPort}. */
    static final class FakeLiveUpdatePublisherPort implements LiveUpdatePublisherPort {
        final List<MapEvent> events = new ArrayList<>();

        @Override
        public void publishFleetChanged() {
        }

        @Override
        public void publishTelemetryAppended(AssetId assetId, Telemetry sample) {
        }

        @Override
        public void publishDetections(AssetId assetId, DetectionResult result) {
        }

        @Override
        public void publishEvent(Event event) {
        }

        @Override
        public void publishDetectionEvent(DetectionEvent event) {
        }

        @Override
        public void publishMapEvent(MapEvent event) {
            events.add(event);
        }
    }
}
