package com.drones.vision.identity.application.handover;

import com.drones.vision.identity.application.AssignmentService;
import com.drones.vision.identity.domain.model.AssignmentRole;
import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.CategoryId;
import com.drones.vision.kernel.DeviceId;
import com.drones.vision.kernel.GroupId;
import com.drones.vision.kernel.Ownership;
import com.drones.vision.kernel.UserId;
import com.drones.vision.platform.AccessDeniedException;
import com.drones.vision.platform.Authority;
import com.drones.vision.platform.Capability;
import com.drones.vision.platform.VisibilityScope;
import com.drones.vision.warehouse.application.custody.AssetCustodyService;
import com.drones.vision.warehouse.domain.model.Asset;
import com.drones.vision.warehouse.domain.model.Custody;
import com.drones.vision.warehouse.domain.model.Identity;
import com.drones.vision.warehouse.domain.model.MaintenanceKind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Hand-written fakes rather than mocks: every assertion here is about the <em>sequence</em> of two
 * calls and what survives a failure between them, which reads far better as a recorded call log than
 * as verification stubs.
 */
class DefaultHandoverServiceTest {

    private static final CategoryId DRONE = new CategoryId("drone");

    private final UserId custodian = UserId.random();
    private final UserId actor = UserId.random();
    private final GroupId group = GroupId.random();

    private FakeAssetCustodyService custody;
    private FakeAssignmentService assignments;
    private HandoverService service;
    private Asset asset;
    private Authority manager;

    @BeforeEach
    void setUp() {
        asset = Asset.register(AssetId.random(), "backfire", DRONE, new Ownership(UserId.random(), group),
                Set.of(DeviceId.random()), Map.of(), Identity.NONE, Custody.NONE);
        custody = new FakeAssetCustodyService(asset);
        assignments = new FakeAssignmentService();
        service = new DefaultHandoverService(custody, assignments);
        manager = new Authority(VisibilityScope.groups(Set.of(group)), Set.of(Capability.MANAGE_FLEET));
    }

    @Test
    void issueWritesCustodyThenAssignsThePilotSeat() {
        service.issue(asset.id(), custodian, "Shelf B", actor, manager);

        assertEquals(List.of("issue:" + custodian.value() + "@Shelf B"), custody.calls);
        assertEquals(Optional.of(AssignmentRole.PILOT), assignments.roleFor(custodian, asset.id()));
        assertEquals(List.of("assign:" + custodian.value() + ":PILOT"), assignments.calls);
    }

    @Test
    void issueReturnsWhateverCustodyReturned() {
        assertSame(custody.issued, service.issue(asset.id(), custodian, null, actor, manager));
    }

    @Test
    void issueLeavesAnExistingPilotAssignmentAloneAndWritesNoSecondGrant() {
        assignments.seed(custodian, asset.id(), AssignmentRole.PILOT);

        service.issue(asset.id(), custodian, null, actor, manager);

        assertTrue(assignments.calls.isEmpty(), "an already-assigned pilot must not be re-granted");
        assertEquals(Optional.of(AssignmentRole.PILOT), assignments.roleFor(custodian, asset.id()));
    }

    @Test
    void issueNeverPromotesAnExistingCrewSeatToPilot() {
        // A manager chose CREW for this person on this asset; handing them the box is not a
        // decision to change that (HandoverService#issue's own contract).
        assignments.seed(custodian, asset.id(), AssignmentRole.CREW);

        service.issue(asset.id(), custodian, null, actor, manager);

        assertEquals(Optional.of(AssignmentRole.CREW), assignments.roleFor(custodian, asset.id()));
        assertTrue(assignments.calls.isEmpty());
    }

    @Test
    void aFailedCustodyWriteNeverReachesTheAssignment() {
        custody.failIssueWith = new IllegalStateException("already issued");

        assertThrows(IllegalStateException.class, () -> service.issue(asset.id(), custodian, null, actor, manager));

        assertTrue(assignments.calls.isEmpty());
        assertEquals(List.of("issue:" + custodian.value() + "@null"), custody.calls);
    }

    @Test
    void aFailedAssignmentCompensatesTheCustodyWriteAndRethrows() {
        assignments.failAssignWith = new AccessDeniedException("outside your management authority");

        AccessDeniedException thrown = assertThrows(AccessDeniedException.class,
                () -> service.issue(asset.id(), custodian, null, actor, manager));

        assertEquals("outside your management authority", thrown.getMessage());
        assertEquals(List.of("issue:" + custodian.value() + "@null", "returnToStock"), custody.calls);
        assertEquals(Optional.empty(), assignments.roleFor(custodian, asset.id()));
    }

    @Test
    void aFailedCompensationIsSuppressedSoTheOriginalFailureStillSurfaces() {
        assignments.failAssignWith = new IllegalStateException("roster write failed");
        custody.failReturnWith = new NoSuchElementException("asset vanished");

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> service.issue(asset.id(), custodian, null, actor, manager));

        assertEquals("roster write failed", thrown.getMessage());
        assertEquals(1, thrown.getSuppressed().length);
        assertEquals("asset vanished", thrown.getSuppressed()[0].getMessage());
    }

    @Test
    void returnToStockClearsCustodyAndLeavesTheAssignmentInPlace() {
        assignments.seed(custodian, asset.id(), AssignmentRole.PILOT);

        service.returnToStock(asset.id(), actor, manager);

        assertEquals(List.of("returnToStock"), custody.calls);
        assertEquals(Optional.of(AssignmentRole.PILOT), assignments.roleFor(custodian, asset.id()),
                "authorisation to fly outlives possession (INVENTORY-REWORK-PLAN D2)");
    }

    @Test
    void returnToStockPropagatesCustodysOwnRefusal() {
        custody.failReturnWith = new IllegalStateException("not issued");

        assertThrows(IllegalStateException.class, () -> service.returnToStock(asset.id(), actor, manager));
    }

    /** Records what it was asked to do; fails on demand. Only the two custody verbs are exercised. */
    private static final class FakeAssetCustodyService implements AssetCustodyService {

        private final List<String> calls = new ArrayList<>();
        private final Asset issued;
        private RuntimeException failIssueWith;
        private RuntimeException failReturnWith;

        private FakeAssetCustodyService(Asset asset) {
            this.issued = asset.withInventory(new Custody(UserId.random(), "Shelf B", Instant.now()),
                    asset.inventoryState(), Instant.now());
        }

        @Override
        public Asset issue(AssetId id, UserId custodianId, String location, UserId actor, Authority scope) {
            calls.add("issue:" + custodianId.value() + "@" + location);
            if (failIssueWith != null) {
                throw failIssueWith;
            }
            return issued;
        }

        @Override
        public Asset returnToStock(AssetId id, UserId actor, Authority scope) {
            calls.add("returnToStock");
            if (failReturnWith != null) {
                throw failReturnWith;
            }
            return issued;
        }

        @Override
        public Asset ground(AssetId id, MaintenanceKind kind, String summary, UserId actor, Authority scope) {
            throw new UnsupportedOperationException("HandoverService never grounds");
        }

        @Override
        public Asset release(AssetId id, UserId actor, Authority scope) {
            throw new UnsupportedOperationException("HandoverService never releases");
        }

        @Override
        public Asset retire(AssetId id, UserId actor, Authority scope) {
            throw new UnsupportedOperationException("HandoverService never retires");
        }
    }

    /** An in-heap roster with a recorded write log; {@code unassign} is never exercised by design. */
    private static final class FakeAssignmentService implements AssignmentService {

        private final Map<String, AssignmentRole> links = new HashMap<>();
        private final List<String> calls = new ArrayList<>();
        private RuntimeException failAssignWith;

        private void seed(UserId pilot, AssetId asset, AssignmentRole role) {
            links.put(key(pilot, asset), role);
        }

        private static String key(UserId pilot, AssetId asset) {
            return pilot.value() + ":" + asset.value();
        }

        @Override
        public void assign(UserId pilot, AssetId asset, AssignmentRole role, UserId actor, Authority granterScope) {
            if (failAssignWith != null) {
                throw failAssignWith;
            }
            calls.add("assign:" + pilot.value() + ":" + role);
            links.put(key(pilot, asset), role);
        }

        @Override
        public void unassign(UserId pilot, AssetId asset, UserId actor, Authority granterScope) {
            calls.add("unassign:" + pilot.value());
            links.remove(key(pilot, asset));
        }

        @Override
        public Set<AssetId> assignmentsFor(UserId pilot) {
            throw new UnsupportedOperationException("HandoverService never lists a pilot's roster");
        }

        @Override
        public Optional<AssignmentRole> roleFor(UserId pilot, AssetId asset) {
            return Optional.ofNullable(links.get(key(pilot, asset)));
        }
    }
}
