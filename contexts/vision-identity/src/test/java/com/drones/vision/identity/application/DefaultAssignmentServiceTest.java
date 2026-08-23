package com.drones.vision.identity.application;

import com.drones.vision.warehouse.domain.model.Asset;
import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.CategoryId;
import com.drones.vision.kernel.DeviceId;
import com.drones.vision.kernel.GroupId;
import com.drones.vision.kernel.Ownership;
import com.drones.vision.kernel.UserId;
import com.drones.vision.warehouse.domain.port.AssetRepositoryPort;
import com.drones.vision.identity.domain.port.AssignmentRepositoryPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
import com.drones.vision.platform.VisibilityScope;

class DefaultAssignmentServiceTest {

    private static final CategoryId DRONE = new CategoryId("drone");

    private AssetRepositoryPort assetRepository;
    private FakeAssignmentRepositoryPort assignmentRepository;
    private AssignmentService service;

    private final UserId pilot = UserId.random();
    private final GroupId group = GroupId.random();
    private Asset asset;

    @BeforeEach
    void setUp() {
        assetRepository = mock(AssetRepositoryPort.class);
        assignmentRepository = new FakeAssignmentRepositoryPort();
        service = new DefaultAssignmentService(assignmentRepository, assetRepository);

        asset = new Asset(AssetId.random(), "drone", DRONE, new Ownership(UserId.random(), group),
                Set.of(DeviceId.random()), Map.of());
        when(assetRepository.findById(asset.id())).thenReturn(Optional.of(asset));
    }

    @Test
    void assignWithinGranterScopeStoresTheLink() {
        service.assign(pilot, asset.id(), VisibilityScope.groups(Set.of(group)));

        assertTrue(assignmentRepository.isAssigned(pilot, asset.id()));
    }

    @Test
    void assignIsAllowedForAnUnboundedGranter() {
        service.assign(pilot, asset.id(), VisibilityScope.unbounded());

        assertTrue(assignmentRepository.isAssigned(pilot, asset.id()));
    }

    @Test
    void assignOutsideGranterScopeIsDeniedAndStoresNothing() {
        assertThrows(AccessDeniedException.class,
                () -> service.assign(pilot, asset.id(), VisibilityScope.groups(Set.of())));

        assertFalse(assignmentRepository.isAssigned(pilot, asset.id()));
    }

    @Test
    void aPilotScopeMayNotGrantEvenWhenTheAssetIsAlreadyAssignedToThem() {
        // Authority is not visibility (docs/plans/done/OPS-UX-PLAN.md §1): a pilot assigned to
        // this very asset can see it, but seeing it is not authority to re-pilot it.
        VisibilityScope pilotScope = VisibilityScope.assignedAssets(Set.of(asset.id()));

        assertThrows(AccessDeniedException.class, () -> service.assign(pilot, asset.id(), pilotScope));

        assertFalse(assignmentRepository.isAssigned(pilot, asset.id()));
    }

    @Test
    void assignUnknownAssetThrowsNoSuchElement() {
        AssetId unknown = AssetId.random();
        when(assetRepository.findById(unknown)).thenReturn(Optional.empty());

        assertThrows(NoSuchElementException.class,
                () -> service.assign(pilot, unknown, VisibilityScope.unbounded()));
    }

    @Test
    void assignIsIdempotent() {
        service.assign(pilot, asset.id(), VisibilityScope.unbounded());
        service.assign(pilot, asset.id(), VisibilityScope.unbounded());

        assertEquals(Set.of(asset.id()), service.assignmentsFor(pilot));
    }

    @Test
    void unassignRemovesTheLink() {
        service.assign(pilot, asset.id(), VisibilityScope.unbounded());

        service.unassign(pilot, asset.id(), VisibilityScope.unbounded());

        assertFalse(assignmentRepository.isAssigned(pilot, asset.id()));
    }

    @Test
    void unassignOutsideGranterScopeIsDenied() {
        service.assign(pilot, asset.id(), VisibilityScope.unbounded());

        assertThrows(AccessDeniedException.class,
                () -> service.unassign(pilot, asset.id(), VisibilityScope.groups(Set.of())));
        assertTrue(assignmentRepository.isAssigned(pilot, asset.id()));
    }

    @Test
    void aPilotScopeMayNotUnassignEvenTheirOwnAssignment() {
        service.assign(pilot, asset.id(), VisibilityScope.unbounded());
        VisibilityScope pilotScope = VisibilityScope.assignedAssets(Set.of(asset.id()));

        assertThrows(AccessDeniedException.class, () -> service.unassign(pilot, asset.id(), pilotScope));

        assertTrue(assignmentRepository.isAssigned(pilot, asset.id()));
    }

    @Test
    void assignmentsForReturnsThePilotsAssets() {
        service.assign(pilot, asset.id(), VisibilityScope.unbounded());

        assertEquals(Set.of(asset.id()), service.assignmentsFor(pilot));
    }

    @Test
    void constructorRejectsNullCollaborators() {
        assertThrows(NullPointerException.class,
                () -> new DefaultAssignmentService(null, assetRepository));
        assertThrows(NullPointerException.class,
                () -> new DefaultAssignmentService(assignmentRepository, null));
    }

    /** In-memory {@link AssignmentRepositoryPort}. */
    private static final class FakeAssignmentRepositoryPort implements AssignmentRepositoryPort {
        private final Map<UserId, Set<AssetId>> byPilot = new ConcurrentHashMap<>();

        @Override
        public void assign(UserId pilot, AssetId asset) {
            byPilot.computeIfAbsent(pilot, k -> new HashSet<>()).add(asset);
        }

        @Override
        public void unassign(UserId pilot, AssetId asset) {
            byPilot.getOrDefault(pilot, new HashSet<>()).remove(asset);
        }

        @Override
        public Set<AssetId> assetsForPilot(UserId pilot) {
            return Set.copyOf(byPilot.getOrDefault(pilot, Set.of()));
        }

        @Override
        public Set<UserId> pilotsForAsset(AssetId asset) {
            Set<UserId> pilots = new HashSet<>();
            byPilot.forEach((pilot, assets) -> {
                if (assets.contains(asset)) {
                    pilots.add(pilot);
                }
            });
            return pilots;
        }

        @Override
        public boolean isAssigned(UserId pilot, AssetId asset) {
            return byPilot.getOrDefault(pilot, Set.of()).contains(asset);
        }
    }
}
