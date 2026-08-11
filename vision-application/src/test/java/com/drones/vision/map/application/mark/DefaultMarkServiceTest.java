package com.drones.vision.map.application.mark;

import com.drones.vision.map.domain.model.Affiliation;
import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.DeviceId;
import com.drones.vision.kernel.GeoPosition;
import com.drones.vision.kernel.GeoProjection;
import com.drones.vision.kernel.GroupId;
import com.drones.vision.map.domain.model.LayerId;
import com.drones.vision.map.domain.model.LayerKind;
import com.drones.vision.map.domain.model.MapEvent;
import com.drones.vision.map.domain.model.MapLayer;
import com.drones.vision.map.domain.model.Mark;
import com.drones.vision.map.domain.model.MarkId;
import com.drones.vision.map.domain.model.MarkKind;
import com.drones.vision.map.domain.model.MarkSource;
import com.drones.vision.map.domain.model.MarkStatus;
import com.drones.vision.kernel.Ownership;
import com.drones.vision.identity.domain.model.Role;
import com.drones.vision.flight.domain.model.Telemetry;
import com.drones.vision.kernel.UserId;
import com.drones.vision.map.domain.model.Verification;
import com.drones.vision.map.domain.model.Verification.VerificationState;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import com.drones.vision.map.application.LayerResolver;
import com.drones.vision.map.application.MapAccessPolicy;
import com.drones.vision.map.application.MapAccessPolicy.Viewer;
import com.drones.vision.perception.application.pipeline.UsageTracker;
import com.drones.vision.platform.AccessDeniedException;

class DefaultMarkServiceTest {

    private FakeMarkRepositoryPort markRepository;
    private FakeMapLayerRepositoryPort mapLayerRepository;
    private UsageTracker usageTracker;
    private FakeMapLiveUpdatePort liveUpdatePublisher;
    private LayerResolver layerResolver;
    private MarkService service;

    private final UserId creator = UserId.random();
    private final GroupId group = GroupId.random();
    private final Ownership ownership = new Ownership(creator, group);
    private final AssetId assetId = AssetId.random();

    private MapLayer cop;
    private MapLayer team;

    @BeforeEach
    void setUp() {
        markRepository = new FakeMarkRepositoryPort();
        mapLayerRepository = new FakeMapLayerRepositoryPort();
        usageTracker = mock(UsageTracker.class);
        liveUpdatePublisher = new FakeMapLiveUpdatePort();
        layerResolver = new LayerResolver(mapLayerRepository, liveUpdatePublisher);
        service = new DefaultMarkService(markRepository, usageTracker, liveUpdatePublisher, new MapAccessPolicy(),
                layerResolver);

        cop = mapLayerRepository.save(new MapLayer(LayerId.random(), "Common picture", LayerKind.COP,
                new Ownership(UserId.random(), GroupId.random()), List.of(), Instant.now()));
        team = mapLayerRepository.save(new MapLayer(LayerId.random(), "Alpha team", LayerKind.TEAM,
                new Ownership(UserId.random(), group), List.of(), Instant.now()));
        liveUpdatePublisher.reset();
    }

    private static GeoPosition position() {
        return new GeoPosition(50.45, 30.52, null);
    }

    private Viewer pilotViewer(UserId userId) {
        return new Viewer(userId, Set.of(group), Role.PILOT);
    }

    private Viewer managerViewer(UserId userId, GroupId managedGroup) {
        return new Viewer(userId, Set.of(managedGroup), Role.MANAGER);
    }

    private Viewer adminViewer(UserId userId) {
        return new Viewer(userId, Set.of(), Role.ADMIN);
    }

    private Mark mark(LayerId layerId, Ownership owner, MarkStatus status, Verification verification) {
        return new Mark(MarkId.random(), layerId, position(), MarkKind.TARGET, Affiliation.HOSTILE, "Bunker", null,
                owner, Instant.now(), status, MarkSource.MANUAL, verification);
    }

    private Telemetry telemetry(Double lat, Double lon, Double heading, Double altitude) {
        return new Telemetry(DeviceId.random(), Instant.now(), lat, lon, altitude, heading, 80.0, Map.of());
    }

    // --- create ------------------------------------------------------------

    @Test
    void createOnExplicitLayerSetsOwnershipUnverifiedActiveManualAndPublishesCreated() {
        MarkSpec spec = new MarkSpec(team.id(), MarkKind.HAZARD, Affiliation.UNKNOWN, "Wire", "low visibility",
                position());

        Mark created = service.create(pilotViewer(creator), spec);

        assertEquals(creator, created.ownership().ownerId());
        assertEquals(team.id(), created.layerId());
        assertEquals(MarkStatus.ACTIVE, created.status());
        assertEquals(MarkSource.MANUAL, created.source());
        assertEquals(MarkKind.HAZARD, created.kind());
        assertEquals(Affiliation.UNKNOWN, created.affiliation());
        assertEquals("Wire", created.label());
        assertEquals(position(), created.position());
        assertEquals(VerificationState.UNVERIFIED, created.verification().state());
        assertEquals(created, markRepository.findById(created.id()).orElseThrow());
        assertEquals(1, liveUpdatePublisher.events.size());
        MapEvent event = liveUpdatePublisher.events.get(0);
        assertEquals(MapEvent.EntityType.MARK, event.entity());
        assertEquals(MapEvent.Action.CREATED, event.action());
        assertEquals(created, event.payload());
    }

    @Test
    void createWithoutContributeAccessIsDenied() {
        MarkSpec spec = new MarkSpec(team.id(), MarkKind.HAZARD, Affiliation.UNKNOWN, "Wire", null, position());
        Viewer outsider = new Viewer(UserId.random(), Set.of(), Role.PILOT);

        assertThrows(AccessDeniedException.class, () -> service.create(outsider, spec));
        assertTrue(markRepository.findAll().isEmpty());
    }

    @Test
    void createOnUnknownLayerThrowsNoSuchElement() {
        MarkSpec spec = new MarkSpec(LayerId.random(), MarkKind.HAZARD, Affiliation.UNKNOWN, "Wire", null,
                position());

        assertThrows(NoSuchElementException.class, () -> service.create(pilotViewer(creator), spec));
    }

    @Test
    void createRejectsNullCollaborators() {
        MarkSpec spec = new MarkSpec(team.id(), MarkKind.HAZARD, Affiliation.UNKNOWN, "Wire", null, position());
        assertThrows(NullPointerException.class, () -> service.create(null, spec));
        assertThrows(NullPointerException.class, () -> service.create(pilotViewer(creator), null));
    }

    // --- create: default-layer selection ------------------------------------

    @Test
    void createWithNoLayerUsesTheCreatorsFirstTeamLayerByName() {
        MapLayer betaTeam = mapLayerRepository.save(new MapLayer(LayerId.random(), "Beta team", LayerKind.TEAM,
                new Ownership(UserId.random(), group), List.of(), Instant.now()));
        MarkSpec spec = new MarkSpec(null, MarkKind.POI, Affiliation.NEUTRAL, "Wire", null, position());

        Mark created = service.create(pilotViewer(creator), spec);

        // "Alpha team" sorts before "Beta team" case-insensitively.
        assertEquals(team.id(), created.layerId());
        assertNotNull(mapLayerRepository.findById(betaTeam.id()));
    }

    @Test
    void createWithNoLayerAndNoTeamMembershipAutoCreatesAPersonalLayer() {
        Viewer soloPilot = new Viewer(UserId.random(), Set.of(), Role.PILOT);
        MarkSpec spec = new MarkSpec(null, MarkKind.POI, Affiliation.NEUTRAL, "Wire", null, position());

        Mark created = service.create(soloPilot, spec);

        MapLayer personal = mapLayerRepository.findById(created.layerId()).orElseThrow();
        assertEquals(LayerKind.PERSONAL, personal.kind());
        assertEquals(soloPilot.userId(), personal.ownership().ownerId());

        // Calling again reuses the same auto-created personal layer (idempotent).
        Mark second = service.create(soloPilot, spec);
        assertEquals(created.layerId(), second.layerId());
    }

    // --- geolocate -----------------------------------------------------------

    @Test
    void geolocateProjectsFromLatestTelemetryAndPublishesCreated() {
        Telemetry sample = telemetry(50.45, 30.52, 0.0, 100.0);
        when(usageTracker.latestTelemetry(assetId)).thenReturn(Optional.of(sample));
        GeolocateSpec spec = new GeolocateSpec(assetId, team.id(), MarkKind.TARGET, Affiliation.HOSTILE, "Contact",
                null, GeoProjection.DEFAULT_DEPRESSION_DEGREES);

        Mark created = service.geolocate(pilotViewer(creator), spec);

        GeoPosition expected = GeoProjection.project(new GeoPosition(50.45, 30.52, 100.0), 0.0, 100.0,
                GeoProjection.DEFAULT_DEPRESSION_DEGREES);
        assertEquals(expected, created.position());
        assertEquals(MarkSource.DETECTION, created.source());
        assertEquals(MarkStatus.ACTIVE, created.status());
        assertEquals(creator, created.ownership().ownerId());
        assertEquals(team.id(), created.layerId());
        assertEquals(created, markRepository.findById(created.id()).orElseThrow());
        assertEquals(1, liveUpdatePublisher.events.size());
    }

    @Test
    void geolocateWithoutContributeAccessIsDeniedBeforeTouchingTelemetry() {
        GeolocateSpec spec = new GeolocateSpec(assetId, team.id(), MarkKind.TARGET, Affiliation.HOSTILE, "Contact",
                null, 45.0);
        Viewer outsider = new Viewer(UserId.random(), Set.of(), Role.PILOT);

        assertThrows(AccessDeniedException.class, () -> service.geolocate(outsider, spec));
        assertTrue(markRepository.findAll().isEmpty());
    }

    @Test
    void geolocateThrowsWhenTheAssetHasNeverReportedTelemetry() {
        when(usageTracker.latestTelemetry(assetId)).thenReturn(Optional.empty());
        GeolocateSpec spec = new GeolocateSpec(assetId, team.id(), MarkKind.TARGET, Affiliation.HOSTILE, "Contact",
                null, 45.0);

        assertThrows(IllegalArgumentException.class, () -> service.geolocate(pilotViewer(creator), spec));
        assertTrue(markRepository.findAll().isEmpty());
        assertTrue(liveUpdatePublisher.events.isEmpty());
    }

    @Test
    void geolocateThrowsWhenPositionIsMissing() {
        when(usageTracker.latestTelemetry(assetId)).thenReturn(Optional.of(telemetry(null, null, 0.0, 100.0)));
        GeolocateSpec spec = new GeolocateSpec(assetId, team.id(), MarkKind.TARGET, Affiliation.HOSTILE, "Contact",
                null, 45.0);

        assertThrows(IllegalArgumentException.class, () -> service.geolocate(pilotViewer(creator), spec));
        assertTrue(markRepository.findAll().isEmpty());
    }

    @Test
    void geolocateThrowsWhenHeadingIsMissing() {
        when(usageTracker.latestTelemetry(assetId)).thenReturn(Optional.of(telemetry(50.45, 30.52, null, 100.0)));
        GeolocateSpec spec = new GeolocateSpec(assetId, team.id(), MarkKind.TARGET, Affiliation.HOSTILE, "Contact",
                null, 45.0);

        assertThrows(IllegalArgumentException.class, () -> service.geolocate(pilotViewer(creator), spec));
        assertTrue(markRepository.findAll().isEmpty());
    }

    @Test
    void geolocateThrowsWhenAltitudeIsMissing() {
        when(usageTracker.latestTelemetry(assetId)).thenReturn(Optional.of(telemetry(50.45, 30.52, 0.0, null)));
        GeolocateSpec spec = new GeolocateSpec(assetId, team.id(), MarkKind.TARGET, Affiliation.HOSTILE, "Contact",
                null, 45.0);

        assertThrows(IllegalArgumentException.class, () -> service.geolocate(pilotViewer(creator), spec));
        assertTrue(markRepository.findAll().isEmpty());
    }

    @Test
    void geolocateThrowsWhenAltitudeIsNotPositive() {
        when(usageTracker.latestTelemetry(assetId)).thenReturn(Optional.of(telemetry(50.45, 30.52, 0.0, 0.0)));
        GeolocateSpec spec = new GeolocateSpec(assetId, team.id(), MarkKind.TARGET, Affiliation.HOSTILE, "Contact",
                null, 45.0);

        assertThrows(IllegalArgumentException.class, () -> service.geolocate(pilotViewer(creator), spec));
        assertTrue(markRepository.findAll().isEmpty());
    }

    // --- list: layer-scoped, ACTIVE only -----------------------------------

    @Test
    void listReturnsOnlyMarksOnVisibleLayers() {
        Mark onCop = markRepository.save(mark(cop.id(), ownership, MarkStatus.ACTIVE, Verification.unverified()));
        Mark onTeam = markRepository.save(mark(team.id(), ownership, MarkStatus.ACTIVE, Verification.unverified()));
        MapLayer otherTeam = mapLayerRepository.save(new MapLayer(LayerId.random(), "Other team", LayerKind.TEAM,
                new Ownership(UserId.random(), GroupId.random()), List.of(), Instant.now()));
        markRepository.save(mark(otherTeam.id(), new Ownership(UserId.random(), otherTeam.ownership().groupId()),
                MarkStatus.ACTIVE, Verification.unverified()));

        List<Mark> visible = service.list(pilotViewer(UserId.random()));

        // A pilot who is only a member of `group` sees COP + their own team, not the other team.
        assertEquals(Set.of(onCop, onTeam), Set.copyOf(visible));
    }

    @Test
    void listExcludesClearedMarks() {
        Mark active = markRepository.save(mark(cop.id(), ownership, MarkStatus.ACTIVE, Verification.unverified()));
        markRepository.save(mark(cop.id(), ownership, MarkStatus.CLEARED, Verification.unverified()));

        List<Mark> visible = service.list(pilotViewer(creator));

        assertEquals(List.of(active), visible);
    }

    @Test
    void listSortsNewestFirst() {
        Mark older = markRepository.save(new Mark(MarkId.random(), cop.id(), position(), MarkKind.POI,
                Affiliation.NEUTRAL, "old", null, ownership, Instant.now().minusSeconds(60), MarkStatus.ACTIVE,
                MarkSource.MANUAL, Verification.unverified()));
        Mark newer = markRepository.save(new Mark(MarkId.random(), cop.id(), position(), MarkKind.POI,
                Affiliation.NEUTRAL, "new", null, ownership, Instant.now(), MarkStatus.ACTIVE, MarkSource.MANUAL,
                Verification.unverified()));

        assertEquals(List.of(newer, older), service.list(pilotViewer(creator)));
    }

    @Test
    void listRejectsNullViewer() {
        assertThrows(NullPointerException.class, () -> service.list(null));
    }

    // --- patch: creator-while-unverified-or-manager gate --------------------

    @Test
    void patchByCreatorWhileUnverifiedAppliesPresentFieldsAndPublishesUpdated() {
        Mark existing = markRepository.save(mark(team.id(), ownership, MarkStatus.ACTIVE, Verification.unverified()));
        MarkPatch patch = new MarkPatch(Optional.of(MarkKind.UNIT), Optional.of(Affiliation.FRIENDLY),
                Optional.of("Renamed"), Optional.empty(), Optional.empty(), Optional.empty());

        Mark updated = service.patch(pilotViewer(creator), existing.id(), patch);

        assertEquals(MarkKind.UNIT, updated.kind());
        assertEquals(Affiliation.FRIENDLY, updated.affiliation());
        assertEquals("Renamed", updated.label());
        assertEquals(existing.note(), updated.note());
        assertEquals(existing.position(), updated.position());
        assertEquals(MarkStatus.ACTIVE, updated.status());
        assertEquals(1, liveUpdatePublisher.events.size());
        assertEquals(MapEvent.Action.UPDATED, liveUpdatePublisher.events.get(0).action());
    }

    @Test
    void patchByNonCreatorManagerAppliesPresentFieldsAndPublishesUpdated() {
        Mark existing = markRepository.save(mark(team.id(), ownership, MarkStatus.ACTIVE, Verification.unverified()));
        MarkPatch patch = new MarkPatch(Optional.empty(), Optional.empty(), Optional.of("Renamed"), Optional.empty(),
                Optional.empty(), Optional.empty());

        Mark updated = service.patch(managerViewer(UserId.random(), group), existing.id(), patch);

        assertEquals("Renamed", updated.label());
    }

    @Test
    void patchByAdminAlwaysSucceeds() {
        Mark existing = markRepository.save(mark(team.id(), ownership, MarkStatus.ACTIVE, Verification.unverified()));
        MarkPatch patch = new MarkPatch(Optional.empty(), Optional.empty(), Optional.of("Renamed"), Optional.empty(),
                Optional.empty(), Optional.empty());

        Mark updated = service.patch(adminViewer(UserId.random()), existing.id(), patch);

        assertEquals("Renamed", updated.label());
    }

    @Test
    void patchByInScopeNonCreatorNonManagerIsDeniedAndLeavesTheMarkUnchanged() {
        Mark existing = markRepository.save(mark(team.id(), ownership, MarkStatus.ACTIVE, Verification.unverified()));
        MarkPatch patch = new MarkPatch(Optional.empty(), Optional.empty(), Optional.of("Renamed"), Optional.empty(),
                Optional.empty(), Optional.empty());
        // A fellow team member can VIEW the layer, so the denial is an honest 403.
        Viewer teammate = pilotViewer(UserId.random());

        assertThrows(AccessDeniedException.class, () -> service.patch(teammate, existing.id(), patch));
        assertEquals(existing, markRepository.findById(existing.id()).orElseThrow());
        assertTrue(liveUpdatePublisher.events.isEmpty());
    }

    @Test
    void patchOfInvisibleMarkReadsAsUnknown() {
        Mark existing = markRepository.save(mark(team.id(), ownership, MarkStatus.ACTIVE, Verification.unverified()));
        MarkPatch patch = new MarkPatch(Optional.empty(), Optional.empty(), Optional.of("Renamed"), Optional.empty(),
                Optional.empty(), Optional.empty());
        // An outsider may not learn the mark exists (docs/plans/done/MAP-REWORK-PLAN.md §4.1) — 404, not 403.
        Viewer outsider = new Viewer(UserId.random(), Set.of(), Role.PILOT);

        assertThrows(NoSuchElementException.class, () -> service.patch(outsider, existing.id(), patch));
        assertEquals(existing, markRepository.findById(existing.id()).orElseThrow());
        assertTrue(liveUpdatePublisher.events.isEmpty());
    }

    @Test
    void verifyAndPromoteAndDeleteOfInvisibleMarkReadAsUnknown() {
        Mark existing = markRepository.save(mark(team.id(), ownership, MarkStatus.ACTIVE, Verification.unverified()));
        Viewer outsider = new Viewer(UserId.random(), Set.of(), Role.PILOT);

        assertThrows(NoSuchElementException.class,
                () -> service.verify(outsider, existing.id(), VerificationState.CONFIRMED));
        assertThrows(NoSuchElementException.class, () -> service.promote(outsider, existing.id(), null));
        assertThrows(NoSuchElementException.class, () -> service.delete(outsider, existing.id()));
        assertEquals(existing, markRepository.findById(existing.id()).orElseThrow());
        assertTrue(liveUpdatePublisher.events.isEmpty());
    }

    @Test
    void creatorLosesEditRightOnceMarkIsConfirmed() {
        Verification confirmed = new Verification(VerificationState.CONFIRMED, UserId.random(), Instant.now());
        Mark existing = markRepository.save(mark(team.id(), ownership, MarkStatus.ACTIVE, confirmed));
        MarkPatch patch = new MarkPatch(Optional.empty(), Optional.empty(), Optional.of("Renamed"), Optional.empty(),
                Optional.empty(), Optional.empty());

        assertThrows(AccessDeniedException.class, () -> service.patch(pilotViewer(creator), existing.id(), patch));

        // A manager may still edit it.
        Mark updated = service.patch(managerViewer(UserId.random(), group), existing.id(), patch);
        assertEquals("Renamed", updated.label());
    }

    @Test
    void patchUnknownIdThrowsNoSuchElement() {
        MarkId unknown = MarkId.random();
        assertThrows(NoSuchElementException.class, () -> service.patch(adminViewer(creator), unknown, MarkPatch.NOTHING));
    }

    @Test
    void patchStatusToClearedByCreatorSucceedsAndPublishesCleared() {
        Mark existing = markRepository.save(mark(team.id(), ownership, MarkStatus.ACTIVE, Verification.unverified()));
        MarkPatch patch = new MarkPatch(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.of(MarkStatus.CLEARED));

        Mark updated = service.patch(pilotViewer(creator), existing.id(), patch);

        assertEquals(MarkStatus.CLEARED, updated.status());
        assertEquals(MapEvent.Action.CLEARED, liveUpdatePublisher.events.get(0).action());
    }

    @Test
    void patchReopensAClearedMarkBackToActive() {
        Mark cleared = markRepository.save(mark(team.id(), ownership, MarkStatus.CLEARED, Verification.unverified()));
        MarkPatch patch = new MarkPatch(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.of(MarkStatus.ACTIVE));

        Mark reopened = service.patch(pilotViewer(creator), cleared.id(), patch);

        assertEquals(MarkStatus.ACTIVE, reopened.status());
        assertEquals(MapEvent.Action.UPDATED, liveUpdatePublisher.events.get(0).action());
    }

    // --- verify --------------------------------------------------------------

    @Test
    void verifyByManagerConfirmsAndStampsReviewer() {
        Mark existing = markRepository.save(mark(team.id(), ownership, MarkStatus.ACTIVE, Verification.unverified()));
        UserId reviewer = UserId.random();

        Mark verified = service.verify(managerViewer(reviewer, group), existing.id(), VerificationState.CONFIRMED);

        assertEquals(VerificationState.CONFIRMED, verified.verification().state());
        assertEquals(reviewer, verified.verification().verifiedBy());
        assertEquals(MapEvent.Action.UPDATED, liveUpdatePublisher.events.get(0).action());
    }

    @Test
    void verifyByNonManagerIsDenied() {
        Mark existing = markRepository.save(mark(team.id(), ownership, MarkStatus.ACTIVE, Verification.unverified()));

        assertThrows(AccessDeniedException.class,
                () -> service.verify(pilotViewer(creator), existing.id(), VerificationState.CONFIRMED));
    }

    @Test
    void verifyRejectsUnverifiedAsADecision() {
        Mark existing = markRepository.save(mark(team.id(), ownership, MarkStatus.ACTIVE, Verification.unverified()));

        assertThrows(IllegalArgumentException.class,
                () -> service.verify(managerViewer(UserId.random(), group), existing.id(), VerificationState.UNVERIFIED));
    }

    // --- promote ---------------------------------------------------------------

    @Test
    void promoteWithNoTargetDefaultsToCopAndStampsConfirmedVerification() {
        Mark existing = markRepository.save(mark(team.id(), ownership, MarkStatus.ACTIVE, Verification.unverified()));
        UserId manager = UserId.random();

        Mark promoted = service.promote(managerViewer(manager, group), existing.id(), null);

        assertEquals(cop.id(), promoted.layerId());
        assertEquals(VerificationState.CONFIRMED, promoted.verification().state());
        assertEquals(manager, promoted.verification().verifiedBy());
        assertEquals(cop.id(), liveUpdatePublisher.events.get(0).layerId());
    }

    @Test
    void promoteAnAlreadyConfirmedMarkKeepsItsOriginalVerification() {
        Verification original = new Verification(VerificationState.CONFIRMED, UserId.random(), Instant.now().minusSeconds(3600));
        Mark existing = markRepository.save(mark(team.id(), ownership, MarkStatus.ACTIVE, original));

        Mark promoted = service.promote(managerViewer(UserId.random(), group), existing.id(), null);

        assertEquals(original, promoted.verification());
    }

    @Test
    void promoteToExplicitTargetRequiresContributeOnTarget() {
        MapLayer noAccessLayer = mapLayerRepository.save(new MapLayer(LayerId.random(), "Sealed", LayerKind.TEAM,
                new Ownership(UserId.random(), GroupId.random()), List.of(), Instant.now()));
        Mark existing = markRepository.save(mark(team.id(), ownership, MarkStatus.ACTIVE, Verification.unverified()));

        assertThrows(AccessDeniedException.class,
                () -> service.promote(managerViewer(UserId.random(), group), existing.id(), noAccessLayer.id()));
    }

    @Test
    void promoteRequiresManageOnSourceLayer() {
        Mark existing = markRepository.save(mark(team.id(), ownership, MarkStatus.ACTIVE, Verification.unverified()));

        assertThrows(AccessDeniedException.class,
                () -> service.promote(pilotViewer(UserId.random()), existing.id(), null));
    }

    // --- delete ------------------------------------------------------------

    @Test
    void deleteByCreatorRemovesAndPublishesDeleted() {
        Mark existing = markRepository.save(mark(team.id(), ownership, MarkStatus.ACTIVE, Verification.unverified()));

        service.delete(pilotViewer(creator), existing.id());

        assertTrue(markRepository.findById(existing.id()).isEmpty());
        assertEquals(MapEvent.Action.DELETED, liveUpdatePublisher.events.get(0).action());
        assertEquals(existing, liveUpdatePublisher.events.get(0).payload());
    }

    @Test
    void deleteByManagerSucceeds() {
        Mark existing = markRepository.save(mark(team.id(), ownership, MarkStatus.ACTIVE, Verification.unverified()));

        service.delete(managerViewer(UserId.random(), group), existing.id());

        assertTrue(markRepository.findById(existing.id()).isEmpty());
    }

    @Test
    void deleteByNonCreatorNonManagerIsDeniedAndKeepsTheMark() {
        Mark existing = markRepository.save(mark(team.id(), ownership, MarkStatus.ACTIVE, Verification.unverified()));

        assertThrows(AccessDeniedException.class,
                () -> service.delete(pilotViewer(UserId.random()), existing.id()));
        assertTrue(markRepository.findById(existing.id()).isPresent());
    }

    @Test
    void deleteUnknownIdThrowsNoSuchElement() {
        MarkId unknown = MarkId.random();
        assertThrows(NoSuchElementException.class, () -> service.delete(adminViewer(creator), unknown));
    }

    // --- constructor ---------------------------------------------------------

    @Test
    void constructorRejectsNullCollaborators() {
        MapAccessPolicy policy = new MapAccessPolicy();
        assertThrows(NullPointerException.class,
                () -> new DefaultMarkService(null, usageTracker, liveUpdatePublisher, policy, layerResolver));
        assertThrows(NullPointerException.class,
                () -> new DefaultMarkService(markRepository, null, liveUpdatePublisher, policy, layerResolver));
        assertThrows(NullPointerException.class,
                () -> new DefaultMarkService(markRepository, usageTracker, null, policy, layerResolver));
        assertThrows(NullPointerException.class,
                () -> new DefaultMarkService(markRepository, usageTracker, liveUpdatePublisher, null, layerResolver));
        assertThrows(NullPointerException.class,
                () -> new DefaultMarkService(markRepository, usageTracker, liveUpdatePublisher, policy, null));
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
}
