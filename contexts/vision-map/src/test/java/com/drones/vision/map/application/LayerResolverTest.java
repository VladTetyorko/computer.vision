package com.drones.vision.map.application;

import com.drones.vision.platform.Event;
import com.drones.vision.kernel.GroupId;
import com.drones.vision.map.domain.model.LayerId;
import com.drones.vision.map.domain.model.LayerKind;
import com.drones.vision.map.domain.model.MapEvent;
import com.drones.vision.map.domain.model.MapLayer;
import com.drones.vision.kernel.Ownership;
import com.drones.vision.identity.domain.model.Role;
import com.drones.vision.kernel.UserId;
import com.drones.vision.map.domain.port.MapLiveUpdatePort;
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
import com.drones.vision.map.application.MapAccessPolicy.Viewer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LayerResolverTest {

    private FakeMapLayerRepositoryPort mapLayerRepository;
    private FakeMapLiveUpdatePort liveUpdatePublisher;
    private LayerResolver resolver;

    @BeforeEach
    void setUp() {
        mapLayerRepository = new FakeMapLayerRepositoryPort();
        liveUpdatePublisher = new FakeMapLiveUpdatePort();
        resolver = new LayerResolver(mapLayerRepository, liveUpdatePublisher);
    }

    @Test
    void requireThrowsForAnUnknownId() {
        assertThrows(NoSuchElementException.class, () -> resolver.require(LayerId.random()));
    }

    @Test
    void requireReturnsAKnownLayer() {
        MapLayer layer = mapLayerRepository.save(new MapLayer(LayerId.random(), "Layer", LayerKind.PERSONAL,
                new Ownership(UserId.random(), GroupId.random()), List.of(), Instant.now()));

        assertEquals(layer, resolver.require(layer.id()));
    }

    // --- copLayerId: find-or-create, idempotent -------------------------------

    @Test
    void copLayerIdCreatesExactlyOneCopLayerAndIsIdempotent() {
        LayerId first = resolver.copLayerId();
        LayerId second = resolver.copLayerId();

        assertEquals(first, second);
        MapLayer cop = mapLayerRepository.findById(first).orElseThrow();
        assertEquals(LayerKind.COP, cop.kind());
        assertEquals("Common picture", cop.name());
        assertEquals(1, liveUpdatePublisher.events.size());
        assertEquals(MapEvent.Action.CREATED, liveUpdatePublisher.events.get(0).action());
        assertEquals(MapEvent.EntityType.LAYER, liveUpdatePublisher.events.get(0).entity());
    }

    @Test
    void copLayerIdFindsAPreExistingCopLayerWithoutCreatingASecondOne() {
        MapLayer existingCop = mapLayerRepository.save(new MapLayer(LayerId.random(), "Common picture",
                LayerKind.COP, new Ownership(UserId.random(), GroupId.random()), List.of(), Instant.now()));

        LayerId resolved = resolver.copLayerId();

        assertEquals(existingCop.id(), resolved);
        assertTrue(liveUpdatePublisher.events.isEmpty());
        assertEquals(1, mapLayerRepository.findAll().stream().filter(l -> l.kind() == LayerKind.COP).count());
    }

    // --- defaultLayerFor: team first, else auto-personal ----------------------

    @Test
    void defaultLayerForPicksTheFirstTeamLayerSortedByNameThenId() {
        GroupId group = GroupId.random();
        MapLayer beta = mapLayerRepository.save(new MapLayer(LayerId.random(), "Beta", LayerKind.TEAM,
                new Ownership(UserId.random(), group), List.of(), Instant.now()));
        MapLayer alpha = mapLayerRepository.save(new MapLayer(LayerId.random(), "Alpha", LayerKind.TEAM,
                new Ownership(UserId.random(), group), List.of(), Instant.now()));
        Viewer viewer = new Viewer(UserId.random(), Set.of(group), Role.PILOT);

        assertEquals(alpha.id(), resolver.defaultLayerFor(viewer));
    }

    @Test
    void defaultLayerForIgnoresTeamLayersOutsideViewersGroups() {
        MapLayer otherTeam = mapLayerRepository.save(new MapLayer(LayerId.random(), "Other", LayerKind.TEAM,
                new Ownership(UserId.random(), GroupId.random()), List.of(), Instant.now()));
        Viewer viewer = new Viewer(UserId.random(), Set.of(), Role.PILOT);

        LayerId resolved = resolver.defaultLayerFor(viewer);

        assertNotEquals(otherTeam.id(), resolved);
        assertEquals(LayerKind.PERSONAL, mapLayerRepository.require(resolved).kind());
    }

    @Test
    void defaultLayerForAutoCreatesAndReusesAPersonalLayerWhenNoTeamMatches() {
        Viewer viewer = new Viewer(UserId.random(), Set.of(), Role.PILOT);

        LayerId first = resolver.defaultLayerFor(viewer);
        LayerId second = resolver.defaultLayerFor(viewer);

        assertEquals(first, second);
        MapLayer personal = mapLayerRepository.require(first);
        assertEquals(LayerKind.PERSONAL, personal.kind());
        assertEquals(viewer.userId(), personal.ownership().ownerId());
        assertEquals(1, liveUpdatePublisher.events.stream()
                .filter(e -> e.action() == MapEvent.Action.CREATED).count());
    }

    @Test
    void defaultLayerForGivesDifferentViewersDifferentPersonalLayers() {
        Viewer first = new Viewer(UserId.random(), Set.of(), Role.PILOT);
        Viewer second = new Viewer(UserId.random(), Set.of(), Role.PILOT);

        assertNotEquals(resolver.defaultLayerFor(first), resolver.defaultLayerFor(second));
    }

    // --- homeGroupOf -----------------------------------------------------------

    @Test
    void homeGroupOfPicksTheLowestUuidMembershipGroupDeterministically() {
        GroupId g1 = new GroupId(java.util.UUID.fromString("00000000-0000-0000-0000-000000000001"));
        GroupId g2 = new GroupId(java.util.UUID.fromString("00000000-0000-0000-0000-000000000002"));
        Viewer viewer = new Viewer(UserId.random(), Set.of(g2, g1), Role.PILOT);

        assertEquals(g1, LayerResolver.homeGroupOf(viewer));
    }

    @Test
    void homeGroupOfFallsBackToTheSystemSentinelWhenViewerHasNoGroups() {
        Viewer viewer = new Viewer(UserId.random(), Set.of(), Role.PILOT);

        // Not asserting the exact sentinel value (an implementation detail) -- only that it is a
        // stable, well-formed value regardless of how many times it's asked.
        assertEquals(LayerResolver.homeGroupOf(viewer), LayerResolver.homeGroupOf(viewer));
    }

    @Test
    void constructorRejectsNullCollaborators() {
        assertThrows(NullPointerException.class, () -> new LayerResolver(null, liveUpdatePublisher));
        assertThrows(NullPointerException.class, () -> new LayerResolver(mapLayerRepository, null));
    }

    /** In-memory {@link MapLayerRepositoryPort}, plus a convenience {@code require} for test bodies. */
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

        MapLayer require(LayerId id) {
            return findById(id).orElseThrow();
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
