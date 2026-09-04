package com.drones.vision.identity.application;

import com.drones.vision.warehouse.application.asset.AssetDetails;
import com.drones.vision.warehouse.application.asset.AssetService;
import com.drones.vision.warehouse.application.asset.AssetStatus;
import com.drones.vision.warehouse.application.asset.AssetSummary;
import com.drones.vision.warehouse.domain.model.Asset;
import com.drones.vision.warehouse.domain.model.Custody;
import com.drones.vision.warehouse.domain.model.Identity;
import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.CategoryId;
import com.drones.vision.kernel.DeviceId;
import com.drones.vision.kernel.GroupId;
import com.drones.vision.kernel.Ownership;
import com.drones.vision.kernel.UserId;
import com.drones.vision.identity.domain.model.Assignment;
import com.drones.vision.identity.domain.model.AssignmentRole;
import com.drones.vision.identity.domain.port.AssignmentRepositoryPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import com.drones.vision.platform.AccessDeniedException;
import com.drones.vision.platform.AuditAction;
import com.drones.vision.platform.AuditEntry;
import com.drones.vision.platform.AuditTargetType;
import com.drones.vision.platform.AuditTrailPort;
import com.drones.vision.platform.VisibilityScope;

class DefaultAssignmentServiceTest {

    private static final CategoryId DRONE = new CategoryId("drone");

    private AssetService assetService;
    private FakeAssignmentRepositoryPort assignmentRepository;
    private FakeAuditTrailPort auditTrail;
    private AssignmentService service;

    private final UserId pilot = UserId.random();
    private final UserId granter = UserId.random();
    private final GroupId group = GroupId.random();
    private Asset asset;

    @BeforeEach
    void setUp() {
        assetService = mock(AssetService.class);
        assignmentRepository = new FakeAssignmentRepositoryPort();
        auditTrail = new FakeAuditTrailPort();
        service = new DefaultAssignmentService(assignmentRepository, assetService, auditTrail);

        asset = Asset.register(AssetId.random(), "drone", DRONE, new Ownership(UserId.random(), group),
                Set.of(DeviceId.random()), Map.of(), Identity.NONE, Custody.NONE);
        when(assetService.details(asset.id())).thenReturn(detailsOf(asset));
    }

    /** Minimal {@link AssetDetails} wrapping one asset — devices/recentUsages are unused by this service. */
    private static AssetDetails detailsOf(Asset asset) {
        AssetSummary summary = new AssetSummary(asset, "drone", AssetStatus.OFFLINE, null, null,
                asset.inventoryState(), asset.identity(), asset.custody());
        return new AssetDetails(summary, List.of(), List.of());
    }

    @Test
    void assignWithinGranterScopeStoresTheLink() {
        service.assign(pilot, asset.id(), AssignmentRole.PILOT, granter, VisibilityScope.groups(Set.of(group)));

        assertTrue(assignmentRepository.isAssigned(pilot, asset.id()));
        assertEquals(Optional.of(AssignmentRole.PILOT), service.roleFor(pilot, asset.id()));
    }

    @Test
    void assignIsAllowedForAnUnboundedGranter() {
        service.assign(pilot, asset.id(), AssignmentRole.PILOT, granter, VisibilityScope.unbounded());

        assertTrue(assignmentRepository.isAssigned(pilot, asset.id()));
    }

    @Test
    void assignOutsideGranterScopeIsDeniedAndStoresNothing() {
        assertThrows(AccessDeniedException.class,
                () -> service.assign(pilot, asset.id(), AssignmentRole.PILOT, granter, VisibilityScope.groups(Set.of())));

        assertFalse(assignmentRepository.isAssigned(pilot, asset.id()));
        assertTrue(auditTrail.entries.isEmpty(), "a denied grant must not be recorded as one");
    }

    @Test
    void aPilotScopeMayNotGrantEvenWhenTheAssetIsAlreadyAssignedToThem() {
        // Authority is not visibility (docs/plans/done/OPS-UX-PLAN.md §1): a pilot assigned to
        // this very asset can see it, but seeing it is not authority to re-pilot it.
        VisibilityScope pilotScope = VisibilityScope.assignedAssets(Set.of(asset.id()));

        assertThrows(AccessDeniedException.class,
                () -> service.assign(pilot, asset.id(), AssignmentRole.PILOT, granter, pilotScope));

        assertFalse(assignmentRepository.isAssigned(pilot, asset.id()));
    }

    @Test
    void assignUnknownAssetThrowsNoSuchElement() {
        AssetId unknown = AssetId.random();
        when(assetService.details(unknown)).thenThrow(new NoSuchElementException("Unknown asset: " + unknown.value()));

        assertThrows(NoSuchElementException.class,
                () -> service.assign(pilot, unknown, AssignmentRole.PILOT, granter, VisibilityScope.unbounded()));
    }

    @Test
    void assignIsIdempotentAndAuditsOnlyOnce() {
        service.assign(pilot, asset.id(), AssignmentRole.PILOT, granter, VisibilityScope.unbounded());
        service.assign(pilot, asset.id(), AssignmentRole.PILOT, granter, VisibilityScope.unbounded());

        assertEquals(Set.of(asset.id()), service.assignmentsFor(pilot));
        assertEquals(1, auditTrail.entries.size(), "the identical re-assign is a true no-op, not a second grant");
    }

    @Test
    void assignRecordsAGrantedAuditEntryForANewLink() {
        service.assign(pilot, asset.id(), AssignmentRole.CREW, granter, VisibilityScope.unbounded());

        assertEquals(1, auditTrail.entries.size());
        AuditEntry entry = auditTrail.entries.get(0);
        assertEquals(granter, entry.actor());
        assertEquals(AuditAction.GRANTED, entry.action());
        assertEquals(AuditTargetType.ASSIGNMENT, entry.targetType());
        assertEquals(pilot.value() + ":" + asset.id().value(), entry.targetId());
    }

    @Test
    void reassigningWithADifferentRoleRecordsASeatChangeGrant() {
        service.assign(pilot, asset.id(), AssignmentRole.PILOT, granter, VisibilityScope.unbounded());
        service.assign(pilot, asset.id(), AssignmentRole.CREW, granter, VisibilityScope.unbounded());

        assertEquals(Optional.of(AssignmentRole.CREW), service.roleFor(pilot, asset.id()));
        assertEquals(2, auditTrail.entries.size());
        assertEquals(AuditAction.GRANTED, auditTrail.entries.get(1).action());
    }

    @Test
    void unassignRemovesTheLink() {
        service.assign(pilot, asset.id(), AssignmentRole.PILOT, granter, VisibilityScope.unbounded());

        service.unassign(pilot, asset.id(), granter, VisibilityScope.unbounded());

        assertFalse(assignmentRepository.isAssigned(pilot, asset.id()));
    }

    @Test
    void unassignRecordsARevokedAuditEntry() {
        service.assign(pilot, asset.id(), AssignmentRole.PILOT, granter, VisibilityScope.unbounded());

        service.unassign(pilot, asset.id(), granter, VisibilityScope.unbounded());

        assertEquals(2, auditTrail.entries.size());
        AuditEntry revoke = auditTrail.entries.get(1);
        assertEquals(AuditAction.REVOKED, revoke.action());
        assertEquals(AuditTargetType.ASSIGNMENT, revoke.targetType());
    }

    @Test
    void unassigningANonExistentLinkIsANoOpAndAuditsNothing() {
        service.unassign(pilot, asset.id(), granter, VisibilityScope.unbounded());

        assertTrue(auditTrail.entries.isEmpty());
    }

    @Test
    void unassignOutsideGranterScopeIsDenied() {
        service.assign(pilot, asset.id(), AssignmentRole.PILOT, granter, VisibilityScope.unbounded());

        assertThrows(AccessDeniedException.class,
                () -> service.unassign(pilot, asset.id(), granter, VisibilityScope.groups(Set.of())));
        assertTrue(assignmentRepository.isAssigned(pilot, asset.id()));
    }

    @Test
    void aPilotScopeMayNotUnassignEvenTheirOwnAssignment() {
        service.assign(pilot, asset.id(), AssignmentRole.PILOT, granter, VisibilityScope.unbounded());
        VisibilityScope pilotScope = VisibilityScope.assignedAssets(Set.of(asset.id()));

        assertThrows(AccessDeniedException.class, () -> service.unassign(pilot, asset.id(), granter, pilotScope));

        assertTrue(assignmentRepository.isAssigned(pilot, asset.id()));
    }

    @Test
    void assignmentsForReturnsThePilotsAssets() {
        service.assign(pilot, asset.id(), AssignmentRole.PILOT, granter, VisibilityScope.unbounded());

        assertEquals(Set.of(asset.id()), service.assignmentsFor(pilot));
    }

    @Test
    void roleForIsEmptyWhenNotAssigned() {
        assertEquals(Optional.empty(), service.roleFor(pilot, asset.id()));
    }

    @Test
    void constructorRejectsNullCollaborators() {
        assertThrows(NullPointerException.class,
                () -> new DefaultAssignmentService(null, assetService, auditTrail));
        assertThrows(NullPointerException.class,
                () -> new DefaultAssignmentService(assignmentRepository, null, auditTrail));
        assertThrows(NullPointerException.class,
                () -> new DefaultAssignmentService(assignmentRepository, assetService, null));
    }

    /** In-memory {@link AssignmentRepositoryPort}, keyed by (pilot, asset) with each link's seat. */
    private static final class FakeAssignmentRepositoryPort implements AssignmentRepositoryPort {
        private final Map<UserId, Map<AssetId, AssignmentRole>> byPilot = new ConcurrentHashMap<>();

        @Override
        public void assign(UserId pilot, AssetId asset, AssignmentRole role) {
            byPilot.computeIfAbsent(pilot, k -> new HashMap<>()).put(asset, role);
        }

        @Override
        public void unassign(UserId pilot, AssetId asset) {
            Map<AssetId, AssignmentRole> assets = byPilot.get(pilot);
            if (assets != null) {
                assets.remove(asset);
            }
        }

        @Override
        public Set<AssetId> assetsForPilot(UserId pilot) {
            return Set.copyOf(byPilot.getOrDefault(pilot, Map.of()).keySet());
        }

        @Override
        public Set<UserId> pilotsForAsset(AssetId asset) {
            Set<UserId> pilots = new HashSet<>();
            byPilot.forEach((pilot, assets) -> {
                if (assets.containsKey(asset)) {
                    pilots.add(pilot);
                }
            });
            return pilots;
        }

        @Override
        public boolean isAssigned(UserId pilot, AssetId asset) {
            return byPilot.getOrDefault(pilot, Map.of()).containsKey(asset);
        }

        @Override
        public Optional<AssignmentRole> roleFor(UserId pilot, AssetId asset) {
            return Optional.ofNullable(byPilot.getOrDefault(pilot, Map.of()).get(asset));
        }

        @Override
        public List<Assignment> assignmentsForAsset(AssetId asset) {
            List<Assignment> assignments = new ArrayList<>();
            byPilot.forEach((pilot, assets) -> {
                AssignmentRole role = assets.get(asset);
                if (role != null) {
                    assignments.add(new Assignment(pilot, asset, role));
                }
            });
            return List.copyOf(assignments);
        }
    }

    /** In-memory {@link AuditTrailPort}, append-only, in call order. */
    private static final class FakeAuditTrailPort implements AuditTrailPort {
        final List<AuditEntry> entries = new ArrayList<>();

        @Override
        public AuditEntry record(AuditEntry entry) {
            entries.add(entry);
            return entry;
        }

        @Override
        public List<AuditEntry> findRecent(int limit) {
            return List.copyOf(entries);
        }

        @Override
        public List<AuditEntry> findByTarget(AuditTargetType targetType, String targetId, int limit) {
            return entries.stream()
                    .filter(e -> e.targetType() == targetType && e.targetId().equals(targetId))
                    .toList();
        }

        @Override
        public List<AuditEntry> findByActor(UserId actor, int limit) {
            return entries.stream().filter(e -> e.actor().equals(actor)).toList();
        }
    }
}
