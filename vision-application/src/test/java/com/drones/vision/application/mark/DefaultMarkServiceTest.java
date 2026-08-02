package com.drones.vision.application.mark;

import com.drones.vision.domain.model.AssetId;
import com.drones.vision.domain.model.DeviceId;
import com.drones.vision.domain.model.GeoPosition;
import com.drones.vision.domain.model.GeoProjection;
import com.drones.vision.domain.model.GroupId;
import com.drones.vision.domain.model.Mark;
import com.drones.vision.domain.model.MarkId;
import com.drones.vision.domain.model.MarkKind;
import com.drones.vision.domain.model.MarkSource;
import com.drones.vision.domain.model.MarkStatus;
import com.drones.vision.domain.model.Ownership;
import com.drones.vision.domain.model.Telemetry;
import com.drones.vision.domain.model.UserId;
import com.drones.vision.domain.port.out.LiveUpdatePublisherPort;
import com.drones.vision.domain.port.out.MarkRepositoryPort;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import com.drones.vision.application.pipeline.UsageTracker;
import com.drones.vision.application.scope.AccessDeniedException;
import com.drones.vision.application.scope.VisibilityScope;

class DefaultMarkServiceTest {

    private FakeMarkRepositoryPort markRepository;
    private UsageTracker usageTracker;
    private FakeLiveUpdatePublisherPort liveUpdatePublisher;
    private MarkService service;

    private final UserId creator = UserId.random();
    private final GroupId group = GroupId.random();
    private final Ownership ownership = new Ownership(creator, group);
    private final AssetId assetId = AssetId.random();

    @BeforeEach
    void setUp() {
        markRepository = new FakeMarkRepositoryPort();
        usageTracker = mock(UsageTracker.class);
        liveUpdatePublisher = new FakeLiveUpdatePublisherPort();
        service = new DefaultMarkService(markRepository, usageTracker, liveUpdatePublisher);
    }

    private static GeoPosition position() {
        return new GeoPosition(50.45, 30.52, null);
    }

    private Mark mark(Ownership owner, MarkStatus status) {
        return new Mark(MarkId.random(), position(), MarkKind.TARGET, "Bunker", null, owner,
                Instant.now(), status, MarkSource.MANUAL);
    }

    private Telemetry telemetry(Double lat, Double lon, Double heading, Double altitude) {
        return new Telemetry(DeviceId.random(), Instant.now(), lat, lon, altitude, heading, 80.0, Map.of());
    }

    // --- create ----------------------------------------------------------

    @Test
    void createSetsOwnershipActiveManualAndPublishesCreated() {
        MarkSpec spec = new MarkSpec(MarkKind.HAZARD, "Wire", "low visibility", position());

        Mark created = service.create(spec, ownership, creator);

        assertEquals(ownership, created.ownership());
        assertEquals(MarkStatus.ACTIVE, created.status());
        assertEquals(MarkSource.MANUAL, created.source());
        assertEquals(MarkKind.HAZARD, created.kind());
        assertEquals("Wire", created.label());
        assertEquals(position(), created.position());
        assertEquals(created, markRepository.findById(created.id()).orElseThrow());
        assertEquals(created, liveUpdatePublisher.created);
    }

    @Test
    void createRejectsNullCollaborators() {
        MarkSpec spec = new MarkSpec(MarkKind.HAZARD, "Wire", null, position());
        assertThrows(NullPointerException.class, () -> service.create(null, ownership, creator));
        assertThrows(NullPointerException.class, () -> service.create(spec, null, creator));
        assertThrows(NullPointerException.class, () -> service.create(spec, ownership, null));
    }

    // --- geolocate ---------------------------------------------------------

    @Test
    void geolocateProjectsFromLatestTelemetryAndPublishesCreated() {
        Telemetry sample = telemetry(50.45, 30.52, 0.0, 100.0);
        when(usageTracker.latestTelemetry(assetId)).thenReturn(Optional.of(sample));
        GeolocateSpec spec = new GeolocateSpec(assetId, MarkKind.TARGET, "Contact", null,
                GeoProjection.DEFAULT_DEPRESSION_DEGREES);

        Mark created = service.geolocate(spec, ownership, creator);

        GeoPosition expected = GeoProjection.project(new GeoPosition(50.45, 30.52, 100.0), 0.0, 100.0,
                GeoProjection.DEFAULT_DEPRESSION_DEGREES);
        assertEquals(expected, created.position());
        assertEquals(MarkSource.DETECTION, created.source());
        assertEquals(MarkStatus.ACTIVE, created.status());
        assertEquals(ownership, created.ownership());
        assertEquals(created, markRepository.findById(created.id()).orElseThrow());
        assertEquals(created, liveUpdatePublisher.created);
    }

    @Test
    void geolocateThrowsWhenTheAssetHasNeverReportedTelemetry() {
        when(usageTracker.latestTelemetry(assetId)).thenReturn(Optional.empty());
        GeolocateSpec spec = new GeolocateSpec(assetId, MarkKind.TARGET, "Contact", null, 45.0);

        assertThrows(IllegalArgumentException.class, () -> service.geolocate(spec, ownership, creator));
        assertTrue(markRepository.findAll().isEmpty());
        assertNull(liveUpdatePublisher.created);
    }

    @Test
    void geolocateThrowsWhenPositionIsMissing() {
        when(usageTracker.latestTelemetry(assetId)).thenReturn(Optional.of(telemetry(null, null, 0.0, 100.0)));
        GeolocateSpec spec = new GeolocateSpec(assetId, MarkKind.TARGET, "Contact", null, 45.0);

        assertThrows(IllegalArgumentException.class, () -> service.geolocate(spec, ownership, creator));
        assertTrue(markRepository.findAll().isEmpty());
        assertNull(liveUpdatePublisher.created);
    }

    @Test
    void geolocateThrowsWhenHeadingIsMissing() {
        when(usageTracker.latestTelemetry(assetId)).thenReturn(Optional.of(telemetry(50.45, 30.52, null, 100.0)));
        GeolocateSpec spec = new GeolocateSpec(assetId, MarkKind.TARGET, "Contact", null, 45.0);

        assertThrows(IllegalArgumentException.class, () -> service.geolocate(spec, ownership, creator));
        assertTrue(markRepository.findAll().isEmpty());
        assertNull(liveUpdatePublisher.created);
    }

    @Test
    void geolocateThrowsWhenAltitudeIsMissing() {
        when(usageTracker.latestTelemetry(assetId)).thenReturn(Optional.of(telemetry(50.45, 30.52, 0.0, null)));
        GeolocateSpec spec = new GeolocateSpec(assetId, MarkKind.TARGET, "Contact", null, 45.0);

        assertThrows(IllegalArgumentException.class, () -> service.geolocate(spec, ownership, creator));
        assertTrue(markRepository.findAll().isEmpty());
        assertNull(liveUpdatePublisher.created);
    }

    @Test
    void geolocateThrowsWhenAltitudeIsNotPositive() {
        when(usageTracker.latestTelemetry(assetId)).thenReturn(Optional.of(telemetry(50.45, 30.52, 0.0, 0.0)));
        GeolocateSpec spec = new GeolocateSpec(assetId, MarkKind.TARGET, "Contact", null, 45.0);

        assertThrows(IllegalArgumentException.class, () -> service.geolocate(spec, ownership, creator));
        assertTrue(markRepository.findAll().isEmpty());
        assertNull(liveUpdatePublisher.created);
    }

    // --- list: deployment-wide, ACTIVE only -----------------------------

    @Test
    void listReturnsEveryActiveMarkDeploymentWideRegardlessOfOwnerOrGroup() {
        Mark ownMark = markRepository.save(mark(ownership, MarkStatus.ACTIVE));
        Mark otherOwnersMark =
                markRepository.save(mark(new Ownership(UserId.random(), GroupId.random()), MarkStatus.ACTIVE));

        List<Mark> visible = service.list();

        assertEquals(Set.of(ownMark, otherOwnersMark), Set.copyOf(visible));
    }

    @Test
    void listExcludesClearedMarks() {
        Mark active = markRepository.save(mark(ownership, MarkStatus.ACTIVE));
        markRepository.save(mark(ownership, MarkStatus.CLEARED));

        List<Mark> visible = service.list();

        assertEquals(List.of(active), visible);
    }

    @Test
    void listSortsNewestFirst() {
        Mark older = markRepository.save(new Mark(MarkId.random(), position(), MarkKind.POI, "old", null,
                ownership, Instant.now().minusSeconds(60), MarkStatus.ACTIVE, MarkSource.MANUAL));
        Mark newer = markRepository.save(new Mark(MarkId.random(), position(), MarkKind.POI, "new", null,
                ownership, Instant.now(), MarkStatus.ACTIVE, MarkSource.MANUAL));

        assertEquals(List.of(newer, older), service.list());
    }

    // --- update: creator-or-manager gate applies to every field --------

    /**
     * The persona this gate exists for: an FPV operator (PILOT, {@code ASSIGNED_ASSETS} scope,
     * {@code canManageOrg()==false}) drops a mark and must be able to drag-correct/annotate it
     * afterward, even though their scope carries no group and grants no management authority.
     */
    @Test
    void updateByCreatorAppliesPresentFieldsAndPublishesUpdatedEvenWithAPilotScope() {
        Mark existing = markRepository.save(mark(ownership, MarkStatus.ACTIVE));
        MarkPatch patch = new MarkPatch(Optional.of(MarkKind.FRIENDLY), Optional.of("Renamed"),
                Optional.empty(), Optional.empty(), Optional.empty());

        Mark updated = service.update(existing.id(), patch, creator, VisibilityScope.assignedAssets(Set.of()));

        assertEquals(MarkKind.FRIENDLY, updated.kind());
        assertEquals("Renamed", updated.label());
        assertEquals(existing.note(), updated.note());
        assertEquals(existing.position(), updated.position());
        assertEquals(MarkStatus.ACTIVE, updated.status());
        assertEquals(updated, liveUpdatePublisher.updated);
        assertNull(liveUpdatePublisher.cleared);
    }

    @Test
    void updateByNonCreatorManagerAppliesPresentFieldsAndPublishesUpdated() {
        Mark existing = markRepository.save(mark(ownership, MarkStatus.ACTIVE));
        MarkPatch patch = new MarkPatch(Optional.empty(), Optional.of("Renamed"), Optional.empty(),
                Optional.empty(), Optional.empty());

        Mark updated = service.update(existing.id(), patch, UserId.random(), VisibilityScope.unbounded());

        assertEquals("Renamed", updated.label());
        assertEquals(updated, liveUpdatePublisher.updated);
    }

    @Test
    void updateByNonCreatorNonManagerIsDeniedAndLeavesTheMarkUnchanged() {
        Mark existing = markRepository.save(mark(ownership, MarkStatus.ACTIVE));
        MarkPatch patch = new MarkPatch(Optional.empty(), Optional.of("Renamed"), Optional.empty(),
                Optional.empty(), Optional.empty());

        assertThrows(AccessDeniedException.class, () -> service.update(existing.id(), patch, UserId.random(),
                VisibilityScope.assignedAssets(Set.of())));
        assertEquals(existing, markRepository.findById(existing.id()).orElseThrow());
        assertNull(liveUpdatePublisher.updated);
    }

    @Test
    void updateLeavesFieldsUnchangedWhenPatchIsEmpty() {
        Mark existing = markRepository.save(mark(ownership, MarkStatus.ACTIVE));

        Mark updated =
                service.update(existing.id(), MarkPatch.NOTHING, creator, VisibilityScope.assignedAssets(Set.of()));

        assertEquals(existing.kind(), updated.kind());
        assertEquals(existing.label(), updated.label());
        assertEquals(existing.position(), updated.position());
    }

    @Test
    void updateUnknownIdThrowsNoSuchElement() {
        MarkId unknown = MarkId.random();
        assertThrows(NoSuchElementException.class,
                () -> service.update(unknown, MarkPatch.NOTHING, creator, VisibilityScope.unbounded()));
    }

    // --- update: status transition, the same creator-or-manager gate ---

    @Test
    void updateStatusToClearedByCreatorWithAPilotScopeSucceedsAndPublishesCleared() {
        Mark existing = markRepository.save(mark(ownership, MarkStatus.ACTIVE));
        MarkPatch patch = new MarkPatch(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.of(MarkStatus.CLEARED));

        Mark updated = service.update(existing.id(), patch, creator, VisibilityScope.assignedAssets(Set.of()));

        assertEquals(MarkStatus.CLEARED, updated.status());
        assertEquals(updated, liveUpdatePublisher.cleared);
        assertNull(liveUpdatePublisher.updated);
    }

    @Test
    void updateStatusToClearedByNonCreatorManagerSucceeds() {
        Mark existing = markRepository.save(mark(ownership, MarkStatus.ACTIVE));
        MarkPatch patch = new MarkPatch(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.of(MarkStatus.CLEARED));

        Mark updated = service.update(existing.id(), patch, UserId.random(), VisibilityScope.unbounded());

        assertEquals(MarkStatus.CLEARED, updated.status());
        assertEquals(updated, liveUpdatePublisher.cleared);
    }

    @Test
    void updateStatusToClearedByNonCreatorNonManagerIsDeniedAndLeavesTheMarkActive() {
        Mark existing = markRepository.save(mark(ownership, MarkStatus.ACTIVE));
        MarkPatch patch = new MarkPatch(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.of(MarkStatus.CLEARED));

        assertThrows(AccessDeniedException.class, () -> service.update(existing.id(), patch, UserId.random(),
                VisibilityScope.assignedAssets(Set.of())));
        assertEquals(MarkStatus.ACTIVE, markRepository.findById(existing.id()).orElseThrow().status());
        assertNull(liveUpdatePublisher.cleared);
    }

    @Test
    void updateReopensAClearedMarkBackToActiveByItsCreatorAndPublishesUpdated() {
        Mark cleared = markRepository.save(mark(ownership, MarkStatus.CLEARED));
        MarkPatch patch = new MarkPatch(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.of(MarkStatus.ACTIVE));

        Mark reopened = service.update(cleared.id(), patch, creator, VisibilityScope.assignedAssets(Set.of()));

        assertEquals(MarkStatus.ACTIVE, reopened.status());
        assertEquals(reopened, liveUpdatePublisher.updated);
    }

    // --- delete ----------------------------------------------------------

    @Test
    void deleteByCreatorRemovesAndPublishesCleared() {
        Mark existing = markRepository.save(mark(ownership, MarkStatus.ACTIVE));

        service.delete(existing.id(), creator, VisibilityScope.unbounded());

        assertTrue(markRepository.findById(existing.id()).isEmpty());
        assertEquals(existing, liveUpdatePublisher.cleared);
    }

    @Test
    void deleteByManagerSucceeds() {
        Mark existing = markRepository.save(mark(ownership, MarkStatus.ACTIVE));

        service.delete(existing.id(), UserId.random(), VisibilityScope.groups(Set.of(group)));

        assertTrue(markRepository.findById(existing.id()).isEmpty());
        assertEquals(existing, liveUpdatePublisher.cleared);
    }

    @Test
    void deleteByNonCreatorNonManagerIsDeniedAndKeepsTheMark() {
        Mark existing = markRepository.save(mark(ownership, MarkStatus.ACTIVE));

        assertThrows(AccessDeniedException.class,
                () -> service.delete(existing.id(), UserId.random(), VisibilityScope.assignedAssets(Set.of())));
        assertTrue(markRepository.findById(existing.id()).isPresent());
        assertNull(liveUpdatePublisher.cleared);
    }

    @Test
    void deleteUnknownIdThrowsNoSuchElement() {
        MarkId unknown = MarkId.random();
        assertThrows(NoSuchElementException.class,
                () -> service.delete(unknown, creator, VisibilityScope.unbounded()));
    }

    // --- constructor -----------------------------------------------------

    @Test
    void constructorRejectsNullCollaborators() {
        assertThrows(NullPointerException.class,
                () -> new DefaultMarkService(null, usageTracker, liveUpdatePublisher));
        assertThrows(NullPointerException.class,
                () -> new DefaultMarkService(markRepository, null, liveUpdatePublisher));
        assertThrows(NullPointerException.class,
                () -> new DefaultMarkService(markRepository, usageTracker, null));
    }

    /** In-memory {@link MarkRepositoryPort}. */
    private static final class FakeMarkRepositoryPort implements MarkRepositoryPort {
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

    /** Capturing fake {@link LiveUpdatePublisherPort}; only the three {@code publishMark*} methods matter here. */
    private static final class FakeLiveUpdatePublisherPort implements LiveUpdatePublisherPort {
        private Mark created;
        private Mark updated;
        private Mark cleared;

        @Override
        public void publishFleetChanged() {
        }

        @Override
        public void publishTelemetryAppended(AssetId assetId, Telemetry sample) {
        }

        @Override
        public void publishDetections(AssetId assetId, com.drones.vision.domain.model.DetectionResult result) {
        }

        @Override
        public void publishEvent(com.drones.vision.domain.model.Event event) {
        }

        @Override
        public void publishDetectionEvent(com.drones.vision.domain.model.DetectionEvent event) {
        }

        @Override
        public void publishMarkCreated(Mark mark) {
            this.created = mark;
        }

        @Override
        public void publishMarkUpdated(Mark mark) {
            this.updated = mark;
        }

        @Override
        public void publishMarkCleared(Mark mark) {
            this.cleared = mark;
        }
    }
}
