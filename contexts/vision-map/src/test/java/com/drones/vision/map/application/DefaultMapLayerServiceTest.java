package com.drones.vision.map.application;

import com.drones.vision.map.domain.model.AccessLevel;
import com.drones.vision.map.domain.model.Drawing;
import com.drones.vision.map.domain.model.DrawingId;
import com.drones.vision.platform.Event;
import com.drones.vision.kernel.GeoPosition;
import com.drones.vision.kernel.GroupId;
import com.drones.vision.map.domain.model.LayerGrant;
import com.drones.vision.map.domain.model.LayerGrant.SubjectType;
import com.drones.vision.map.domain.model.LayerId;
import com.drones.vision.map.domain.model.LayerKind;
import com.drones.vision.map.domain.model.MapEvent;
import com.drones.vision.map.domain.model.MapLayer;
import com.drones.vision.map.domain.model.Mark;
import com.drones.vision.map.domain.model.MarkId;
import com.drones.vision.map.domain.model.MarkKind;
import com.drones.vision.map.domain.model.Affiliation;
import com.drones.vision.map.domain.model.MarkSource;
import com.drones.vision.map.domain.model.MarkStatus;
import com.drones.vision.kernel.Ownership;
import com.drones.vision.identity.domain.model.Role;
import com.drones.vision.kernel.UserId;
import com.drones.vision.map.domain.model.Verification;
import com.drones.vision.map.domain.port.DrawingRepositoryPort;
import com.drones.vision.map.domain.port.MapLiveUpdatePort;
import com.drones.vision.map.domain.port.MapLayerRepositoryPort;
import com.drones.vision.map.domain.port.MarkRepositoryPort;
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
import com.drones.vision.map.application.MapAccessPolicy.Viewer;
import com.drones.vision.platform.AccessDeniedException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultMapLayerServiceTest {

    private FakeMapLayerRepositoryPort mapLayerRepository;
    private FakeMarkRepositoryPort markRepository;
    private FakeDrawingRepositoryPort drawingRepository;
    private FakeMapLiveUpdatePort liveUpdatePublisher;
    private MapLayerService service;

    @BeforeEach
    void setUp() {
        mapLayerRepository = new FakeMapLayerRepositoryPort();
        markRepository = new FakeMarkRepositoryPort();
        drawingRepository = new FakeDrawingRepositoryPort();
        liveUpdatePublisher = new FakeMapLiveUpdatePort();
        LayerResolver layerResolver = new LayerResolver(mapLayerRepository, liveUpdatePublisher);
        service = new DefaultMapLayerService(layerResolver, markRepository, drawingRepository, liveUpdatePublisher,
                new MapAccessPolicy());
    }

    private static Viewer pilot(UserId id, GroupId... groups) {
        return new Viewer(id, Set.of(groups), Role.PILOT);
    }

    private static Viewer manager(UserId id, GroupId... groups) {
        return new Viewer(id, Set.of(groups), Role.MANAGER);
    }

    private static Viewer admin(UserId id) {
        return new Viewer(id, Set.of(), Role.ADMIN);
    }

    private MapLayer save(String name, LayerKind kind, Ownership ownership) {
        return mapLayerRepository.save(new MapLayer(LayerId.random(), name, kind, ownership, List.of(), Instant.now()));
    }

    // --- layers: COP first, then visible layers by name ------------------------

    @Test
    void layersReturnsCopFirstThenVisibleLayersByNameAndExcludesInvisibleOnes() {
        MapLayer cop = save("Common picture", LayerKind.COP, new Ownership(UserId.random(), GroupId.random()));
        GroupId ownGroup = GroupId.random();
        MapLayer zebraTeam = save("Zebra team", LayerKind.TEAM, new Ownership(UserId.random(), ownGroup));
        MapLayer alphaTeam = save("Alpha team", LayerKind.TEAM, new Ownership(UserId.random(), ownGroup));
        save("Other team", LayerKind.TEAM, new Ownership(UserId.random(), GroupId.random()));

        List<LayerView> views = service.layers(pilot(UserId.random(), ownGroup));

        assertEquals(List.of(cop.id(), alphaTeam.id(), zebraTeam.id()),
                views.stream().map(v -> v.layer().id()).toList());
        assertEquals(AccessLevel.VIEW, views.get(0).myAccess());
        assertEquals(AccessLevel.CONTRIBUTE, views.get(1).myAccess());
    }

    @Test
    void layersRejectsNullViewer() {
        assertThrows(NullPointerException.class, () -> service.layers(null));
    }

    // --- create: TEAM gate, PERSONAL open ---------------------------------------

    @Test
    void createTeamLayerByManagerOfThatGroupSucceeds() {
        GroupId group = GroupId.random();
        UserId managerId = UserId.random();

        MapLayer created = service.create(manager(managerId, group), new LayerSpec("Ops", LayerKind.TEAM, group));

        assertEquals(LayerKind.TEAM, created.kind());
        assertEquals(group, created.ownership().groupId());
        assertEquals(managerId, created.ownership().ownerId());
        assertEquals(1, liveUpdatePublisher.events.size());
        assertEquals(MapEvent.Action.CREATED, liveUpdatePublisher.events.get(0).action());
    }

    @Test
    void createTeamLayerByAdminSucceedsEvenWithoutGroupOverlap() {
        MapLayer created = service.create(admin(UserId.random()),
                new LayerSpec("Ops", LayerKind.TEAM, GroupId.random()));

        assertEquals(LayerKind.TEAM, created.kind());
    }

    @Test
    void createTeamLayerByManagerOfADifferentGroupIsDenied() {
        LayerSpec spec = new LayerSpec("Ops", LayerKind.TEAM, GroupId.random());

        assertThrows(AccessDeniedException.class,
                () -> service.create(manager(UserId.random(), GroupId.random()), spec));
    }

    @Test
    void createTeamLayerByPilotIsDenied() {
        GroupId group = GroupId.random();
        LayerSpec spec = new LayerSpec("Ops", LayerKind.TEAM, group);

        assertThrows(AccessDeniedException.class, () -> service.create(pilot(UserId.random(), group), spec));
    }

    @Test
    void createPersonalLayerIsOpenToAnyone() {
        UserId userId = UserId.random();

        MapLayer created = service.create(pilot(userId), new LayerSpec("My layer", LayerKind.PERSONAL, null));

        assertEquals(LayerKind.PERSONAL, created.kind());
        assertEquals(userId, created.ownership().ownerId());
    }

    // --- rename ------------------------------------------------------------------

    @Test
    void renameByManagerSucceeds() {
        GroupId group = GroupId.random();
        MapLayer team = save("Old name", LayerKind.TEAM, new Ownership(UserId.random(), group));

        MapLayer renamed = service.rename(manager(UserId.random(), group), team.id(), "New name");

        assertEquals("New name", renamed.name());
        assertEquals(MapEvent.Action.UPDATED, liveUpdatePublisher.events.get(0).action());
    }

    @Test
    void renameByInScopeNonManagerIsDenied() {
        GroupId group = GroupId.random();
        MapLayer team = save("Old name", LayerKind.TEAM, new Ownership(UserId.random(), group));

        // A member can VIEW (and contribute to) their team layer, so the denial is an honest 403.
        assertThrows(AccessDeniedException.class,
                () -> service.rename(pilot(UserId.random(), group), team.id(), "New name"));
    }

    @Test
    void renameOfInvisibleLayerReadsAsUnknown() {
        MapLayer team = save("Old name", LayerKind.TEAM, new Ownership(UserId.random(), GroupId.random()));

        // An outsider may not learn the layer exists (docs/plans/done/MAP-REWORK-PLAN.md §4.1) — 404, not 403.
        assertThrows(NoSuchElementException.class,
                () -> service.rename(pilot(UserId.random()), team.id(), "New name"));
    }

    @Test
    void renameCopLayerIsRejectedRegardlessOfRole() {
        MapLayer cop = save("Common picture", LayerKind.COP, new Ownership(UserId.random(), GroupId.random()));

        assertThrows(IllegalStateException.class, () -> service.rename(admin(UserId.random()), cop.id(), "Renamed"));
    }

    @Test
    void renameUnknownIdThrowsNoSuchElement() {
        assertThrows(NoSuchElementException.class,
                () -> service.rename(admin(UserId.random()), LayerId.random(), "Renamed"));
    }

    // --- delete + cascade ----------------------------------------------------------

    @Test
    void deleteCascadesMarksAndDrawingsThenTheLayerItself() {
        GroupId group = GroupId.random();
        MapLayer team = save("Team", LayerKind.TEAM, new Ownership(UserId.random(), group));
        Mark mark = markRepository.save(new Mark(MarkId.random(), team.id(), new GeoPosition(1, 1, null),
                MarkKind.POI, Affiliation.NEUTRAL, "m", null, new Ownership(UserId.random(), group), Instant.now(),
                MarkStatus.ACTIVE, MarkSource.MANUAL, Verification.unverified()));
        Drawing drawing = drawingRepository.save(new Drawing(DrawingId.random(), team.id(),
                com.drones.vision.map.domain.model.DrawKind.LINE,
                List.of(new GeoPosition(1, 1, null), new GeoPosition(2, 2, null)), null, null,
                new Ownership(UserId.random(), group), Instant.now()));

        service.delete(manager(UserId.random(), group), team.id());

        assertTrue(markRepository.findAll().isEmpty());
        assertTrue(drawingRepository.findAll().isEmpty());
        assertTrue(mapLayerRepository.findById(team.id()).isEmpty());

        List<MapEvent> events = liveUpdatePublisher.events;
        assertEquals(3, events.size());
        assertTrue(events.stream().anyMatch(e -> e.entity() == MapEvent.EntityType.MARK
                && e.action() == MapEvent.Action.DELETED && e.payload().equals(mark)));
        assertTrue(events.stream().anyMatch(e -> e.entity() == MapEvent.EntityType.DRAWING
                && e.action() == MapEvent.Action.DELETED && e.payload().equals(drawing)));
        assertTrue(events.stream().anyMatch(e -> e.entity() == MapEvent.EntityType.LAYER
                && e.action() == MapEvent.Action.DELETED));
    }

    @Test
    void deleteByInScopeNonManagerIsDeniedAndLeavesTheLayer() {
        GroupId group = GroupId.random();
        MapLayer team = save("Team", LayerKind.TEAM, new Ownership(UserId.random(), group));

        assertThrows(AccessDeniedException.class, () -> service.delete(pilot(UserId.random(), group), team.id()));
        assertTrue(mapLayerRepository.findById(team.id()).isPresent());
    }

    @Test
    void deleteOfInvisibleLayerReadsAsUnknownAndLeavesTheLayer() {
        MapLayer team = save("Team", LayerKind.TEAM, new Ownership(UserId.random(), GroupId.random()));

        assertThrows(NoSuchElementException.class, () -> service.delete(pilot(UserId.random()), team.id()));
        assertTrue(mapLayerRepository.findById(team.id()).isPresent());
    }

    @Test
    void deleteCopLayerIsRejectedRegardlessOfRole() {
        MapLayer cop = save("Common picture", LayerKind.COP, new Ownership(UserId.random(), GroupId.random()));

        assertThrows(IllegalStateException.class, () -> service.delete(admin(UserId.random()), cop.id()));
        assertTrue(mapLayerRepository.findById(cop.id()).isPresent());
    }

    @Test
    void deleteUnknownIdThrowsNoSuchElement() {
        assertThrows(NoSuchElementException.class, () -> service.delete(admin(UserId.random()), LayerId.random()));
    }

    // --- setGrants -------------------------------------------------------------------

    @Test
    void setGrantsByManagerReplacesWholesale() {
        GroupId group = GroupId.random();
        MapLayer team = save("Team", LayerKind.TEAM, new Ownership(UserId.random(), group));
        List<LayerGrant> grants = List.of(new LayerGrant(SubjectType.USER, UserId.random().value(), AccessLevel.VIEW));

        MapLayer updated = service.setGrants(manager(UserId.random(), group), team.id(), grants);

        assertEquals(grants, updated.grants());
        assertEquals(MapEvent.Action.UPDATED, liveUpdatePublisher.events.get(0).action());
    }

    @Test
    void setGrantsByInScopeNonManagerIsDenied() {
        GroupId group = GroupId.random();
        MapLayer team = save("Team", LayerKind.TEAM, new Ownership(UserId.random(), group));

        assertThrows(AccessDeniedException.class,
                () -> service.setGrants(pilot(UserId.random(), group), team.id(), List.of()));
    }

    @Test
    void setGrantsOnInvisibleLayerReadsAsUnknown() {
        MapLayer team = save("Team", LayerKind.TEAM, new Ownership(UserId.random(), GroupId.random()));

        assertThrows(NoSuchElementException.class,
                () -> service.setGrants(pilot(UserId.random()), team.id(), List.of()));
    }

    // --- copLayerId --------------------------------------------------------------------

    @Test
    void copLayerIdIsIdempotentAndCreatesExactlyOne() {
        LayerId first = service.copLayerId();
        LayerId second = service.copLayerId();

        assertEquals(first, second);
        assertEquals(1, mapLayerRepository.findAll().stream().filter(l -> l.kind() == LayerKind.COP).count());
    }

    @Test
    void constructorRejectsNullCollaborators() {
        LayerResolver layerResolver = new LayerResolver(mapLayerRepository, liveUpdatePublisher);
        MapAccessPolicy policy = new MapAccessPolicy();
        assertThrows(NullPointerException.class,
                () -> new DefaultMapLayerService(null, markRepository, drawingRepository, liveUpdatePublisher, policy));
        assertThrows(NullPointerException.class,
                () -> new DefaultMapLayerService(layerResolver, null, drawingRepository, liveUpdatePublisher, policy));
        assertThrows(NullPointerException.class,
                () -> new DefaultMapLayerService(layerResolver, markRepository, null, liveUpdatePublisher, policy));
        assertThrows(NullPointerException.class,
                () -> new DefaultMapLayerService(layerResolver, markRepository, drawingRepository, null, policy));
        assertThrows(NullPointerException.class,
                () -> new DefaultMapLayerService(layerResolver, markRepository, drawingRepository, liveUpdatePublisher, null));
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

    /** In-memory {@link MarkRepositoryPort}. */
    static final class FakeMarkRepositoryPort implements MarkRepositoryPort {
        private final Map<MarkId, Mark> byId = new ConcurrentHashMap<>();

        @Override
        public Mark save(Mark mark) {
            byId.put(mark.id(), mark);
            return mark;
        }

        @Override
        public Optional<Mark> findById(MarkId id) {
            return Optional.ofNullable(byId.get(id));
        }

        @Override
        public List<Mark> findAll() {
            return List.copyOf(byId.values());
        }

        @Override
        public void deleteById(MarkId id) {
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

    /** Capturing fake {@link MapLiveUpdatePort}. */
    static final class FakeMapLiveUpdatePort implements MapLiveUpdatePort {
        final List<MapEvent> events = new ArrayList<>();

        @Override
        public void publishMapEvent(MapEvent event) {
            events.add(event);
        }
    }
}
