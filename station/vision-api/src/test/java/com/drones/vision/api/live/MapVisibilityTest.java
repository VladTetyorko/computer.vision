package com.drones.vision.api.live;

import com.drones.vision.map.application.LayerView;
import com.drones.vision.map.application.MapAccessPolicy.Viewer;
import com.drones.vision.map.application.MapLayerService;
import com.drones.vision.map.domain.model.AccessLevel;
import com.drones.vision.kernel.GroupId;
import com.drones.vision.map.domain.model.LayerId;
import com.drones.vision.map.domain.model.LayerKind;
import com.drones.vision.map.domain.model.MapLayer;
import com.drones.vision.kernel.Ownership;
import com.drones.vision.identity.domain.model.Role;
import com.drones.vision.kernel.UserId;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link MapVisibility} (docs/plans/done/MAP-REWORK-PLAN.md §4.3) — the per-connection {@code
 * map}-event delivery predicate and its TTL cache.
 *
 * <p>Uses a hand-written {@link MapLayerService} fake rather than Mockito so the number of times the
 * authoritative lookup actually runs is directly observable: the whole point of this class is how
 * often it does <em>not</em> run.
 */
class MapVisibilityTest {

    private static final Ownership OWNERSHIP = new Ownership(UserId.random(), GroupId.random());

    private static Viewer viewer() {
        return new Viewer(UserId.random(), Set.of(GroupId.random()), Role.PILOT);
    }

    private static MapLayer layer(LayerId id) {
        return new MapLayer(id, "Bravo team", LayerKind.TEAM, OWNERSHIP, List.of(), Instant.now());
    }

    /** A {@link MapLayerService} whose visible-layer answer can be swapped, counting every lookup. */
    private static final class FakeLayerService implements MapLayerService {

        private final AtomicInteger lookups = new AtomicInteger();
        private volatile List<LayerId> visible;

        FakeLayerService(List<LayerId> visible) {
            this.visible = visible;
        }

        @Override
        public List<LayerView> layers(Viewer v) {
            lookups.incrementAndGet();
            return visible.stream().map(id -> new LayerView(layer(id), AccessLevel.VIEW)).toList();
        }

        @Override
        public MapLayer create(Viewer v, com.drones.vision.map.application.LayerSpec spec) {
            throw new UnsupportedOperationException();
        }

        @Override
        public MapLayer rename(Viewer v, LayerId id, String name) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void delete(Viewer v, LayerId id) {
            throw new UnsupportedOperationException();
        }

        @Override
        public MapLayer setGrants(Viewer v, LayerId id,
                                   List<com.drones.vision.map.domain.model.LayerGrant> grants) {
            throw new UnsupportedOperationException();
        }

        @Override
        public LayerId copLayerId() {
            throw new UnsupportedOperationException();
        }
    }

    @Test
    void aViewerMaySeeEventsAboutALayerTheyCanView() {
        LayerId visible = LayerId.random();
        MapVisibility visibility = new MapVisibility(new FakeLayerService(List.of(visible)));

        assertTrue(visibility.canView(viewer(), visible.value().toString()));
    }

    @Test
    void aViewerMayNotSeeEventsAboutALayerTheyCannotView() {
        MapVisibility visibility = new MapVisibility(new FakeLayerService(List.of(LayerId.random())));

        assertFalse(visibility.canView(viewer(), LayerId.random().value().toString()));
    }

    @Test
    void aNullLayerIdIsNeverDelivered() {
        MapVisibility visibility = new MapVisibility(new FakeLayerService(List.of(LayerId.random())));

        assertFalse(visibility.canView(viewer(), null),
                "an event with no layer cannot be authorized, so it must not be delivered");
    }

    @Test
    void repeatedPositiveAnswersForOneViewerAreServedFromTheCache() {
        LayerId visible = LayerId.random();
        FakeLayerService service = new FakeLayerService(List.of(visible));
        MapVisibility visibility = new MapVisibility(service);
        Viewer viewer = viewer();

        for (int i = 0; i < 25; i++) {
            assertTrue(visibility.canView(viewer, visible.value().toString()));
        }

        assertEquals(1, service.lookups.get(),
                "a cached 'yes' must not re-run the authoritative lookup once per event");
    }

    @Test
    void aMissRetriesOnceSoAJustCreatedLayerIsNotFilteredOutForAWholeTtl() {
        LayerId knownLayer = LayerId.random();
        LayerId brandNew = LayerId.random();
        FakeLayerService service = new FakeLayerService(List.of(knownLayer));
        MapVisibility visibility = new MapVisibility(service);
        Viewer viewer = viewer();

        // Prime the cache with the pre-creation answer.
        assertTrue(visibility.canView(viewer, knownLayer.value().toString()));
        assertEquals(1, service.lookups.get());

        // The viewer creates a layer; the cached set is now stale in the "no" direction.
        service.visible = List.of(knownLayer, brandNew);

        assertTrue(visibility.canView(viewer, brandNew.value().toString()),
                "a miss must re-resolve rather than answer 'no' from a stale positive set");
        assertEquals(2, service.lookups.get(), "exactly one extra lookup, not one per event");
    }

    @Test
    void repeatedMissesAreBoundedRatherThanReResolvingEveryTime() {
        FakeLayerService service = new FakeLayerService(List.of(LayerId.random()));
        MapVisibility visibility = new MapVisibility(service);
        Viewer viewer = viewer();
        String invisible = LayerId.random().value().toString();

        for (int i = 0; i < 25; i++) {
            assertFalse(visibility.canView(viewer, invisible));
        }

        assertTrue(service.lookups.get() <= 2,
                "a burst of misses inside the negative-TTL window must not re-resolve per event, got "
                        + service.lookups.get());
    }

    @Test
    void twoViewersAreCachedIndependently() {
        LayerId first = LayerId.random();
        LayerId second = LayerId.random();
        MapVisibility visibility = new MapVisibility(new MapLayerServiceByViewer(first, second));
        Viewer alpha = new Viewer(UserId.random(), Set.of(), Role.PILOT);
        Viewer bravo = new Viewer(UserId.random(), Set.of(), Role.PILOT);

        assertTrue(visibility.canView(alpha, first.value().toString()));
        assertFalse(visibility.canView(alpha, second.value().toString()));
        assertTrue(visibility.canView(bravo, second.value().toString()));
        assertFalse(visibility.canView(bravo, first.value().toString()));
    }

    @Test
    void theDeliveryPredicateClosesOverOneViewer() {
        LayerId visible = LayerId.random();
        MapVisibility visibility = new MapVisibility(new FakeLayerService(List.of(visible)));

        Predicate<String> predicate = visibility.deliveryPredicate(viewer());

        assertTrue(predicate.test(visible.value().toString()));
        assertFalse(predicate.test(LayerId.random().value().toString()));
    }

    /** Gives the first-registered viewer one layer and every later viewer the other. */
    private static final class MapLayerServiceByViewer implements MapLayerService {

        private final LayerId first;
        private final LayerId second;
        private volatile Viewer firstViewer;

        MapLayerServiceByViewer(LayerId first, LayerId second) {
            this.first = first;
            this.second = second;
        }

        @Override
        public synchronized List<LayerView> layers(Viewer v) {
            if (firstViewer == null) {
                firstViewer = v;
            }
            LayerId mine = v.equals(firstViewer) ? first : second;
            return List.of(new LayerView(layer(mine), AccessLevel.VIEW));
        }

        @Override
        public MapLayer create(Viewer v, com.drones.vision.map.application.LayerSpec spec) {
            throw new UnsupportedOperationException();
        }

        @Override
        public MapLayer rename(Viewer v, LayerId id, String name) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void delete(Viewer v, LayerId id) {
            throw new UnsupportedOperationException();
        }

        @Override
        public MapLayer setGrants(Viewer v, LayerId id,
                                   List<com.drones.vision.map.domain.model.LayerGrant> grants) {
            throw new UnsupportedOperationException();
        }

        @Override
        public LayerId copLayerId() {
            throw new UnsupportedOperationException();
        }
    }
}
