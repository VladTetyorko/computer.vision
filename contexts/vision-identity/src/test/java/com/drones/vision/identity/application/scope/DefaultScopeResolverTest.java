package com.drones.vision.identity.application.scope;

import com.drones.vision.kernel.AssetId;
import com.drones.vision.identity.domain.model.Assignment;
import com.drones.vision.identity.domain.model.AssignmentRole;
import com.drones.vision.identity.domain.model.Group;
import com.drones.vision.kernel.GroupId;
import com.drones.vision.identity.domain.model.Membership;
import com.drones.vision.identity.domain.model.Role;
import com.drones.vision.identity.domain.model.User;
import com.drones.vision.kernel.UserId;
import com.drones.vision.identity.domain.port.AssignmentRepositoryPort;
import com.drones.vision.identity.domain.port.GroupRepositoryPort;
import com.drones.vision.platform.Authority;
import com.drones.vision.platform.Capability;
import com.drones.vision.platform.VisibilityScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultScopeResolverTest {

    private FakeGroupRepositoryPort groupRepository;
    private FakeAssignmentRepositoryPort assignmentRepository;
    private ScopeResolver resolver;

    // A small tree:  root -> {warehouseA -> {teamA1}, warehouseB};  siblingRoot (separate)
    private final GroupId root = GroupId.random();
    private final GroupId warehouseA = GroupId.random();
    private final GroupId teamA1 = GroupId.random();
    private final GroupId warehouseB = GroupId.random();
    private final GroupId siblingRoot = GroupId.random();

    @BeforeEach
    void setUp() {
        groupRepository = new FakeGroupRepositoryPort();
        assignmentRepository = new FakeAssignmentRepositoryPort();
        resolver = new DefaultScopeResolver(groupRepository, assignmentRepository);

        groupRepository.save(new Group(root, "root", null));
        groupRepository.save(new Group(warehouseA, "warehouse-a", root));
        groupRepository.save(new Group(teamA1, "team-a1", warehouseA));
        groupRepository.save(new Group(warehouseB, "warehouse-b", root));
        groupRepository.save(new Group(siblingRoot, "sibling", null));
    }

    private static User user(Membership... memberships) {
        return new User(UserId.random(), "u", "U", "u@x", "hash", true, false, List.of(memberships));
    }

    @Test
    void adminMembershipAnywhereResolvesToUnbounded() {
        User user = user(new Membership(warehouseA, Role.ADMIN));

        assertTrue(resolver.scopeFor(user).isUnbounded());
    }

    @Test
    void managerSeesItsGroupSubtreeAndExcludesSiblings() {
        User user = user(new Membership(warehouseA, Role.MANAGER));

        VisibilityScope scope = resolver.scopeFor(user);

        assertEquals(VisibilityScope.Kind.GROUPS, scope.kind());
        assertEquals(Set.of(warehouseA, teamA1), scope.groups());
    }

    @Test
    void managerOfRootSeesTheWholeTreeButNotASeparateRoot() {
        User user = user(new Membership(root, Role.MANAGER));

        VisibilityScope scope = resolver.scopeFor(user);

        assertEquals(Set.of(root, warehouseA, teamA1, warehouseB), scope.groups());
        assertTrue(!scope.groups().contains(siblingRoot));
    }

    @Test
    void multipleManagerMembershipsUnionTheirSubtrees() {
        User user = user(new Membership(warehouseA, Role.MANAGER), new Membership(siblingRoot, Role.MANAGER));

        VisibilityScope scope = resolver.scopeFor(user);

        assertEquals(Set.of(warehouseA, teamA1, siblingRoot), scope.groups());
    }

    @Test
    void adminWinsOverAManagerMembership() {
        User user = user(new Membership(warehouseA, Role.MANAGER), new Membership(warehouseB, Role.ADMIN));

        assertTrue(resolver.scopeFor(user).isUnbounded());
    }

    @Test
    void pilotOnlyResolvesToItsAssignedAssets() {
        User user = user(new Membership(warehouseA, Role.PILOT));
        AssetId assigned = AssetId.random();
        assignmentRepository.assign(user.id(), assigned, AssignmentRole.PILOT);

        VisibilityScope scope = resolver.scopeFor(user);

        assertEquals(VisibilityScope.Kind.ASSIGNED_ASSETS, scope.kind());
        assertEquals(Set.of(assigned), scope.assignedAssets());
    }

    @Test
    void viewerSeesItsGroupSubtreeJustLikeAManager() {
        // Wave B6: a VIEWER membership widens scopeFor to the group subtree too, no longer falling
        // through to assignedAssets -- safe now that every canManageOrg()/canManage()/
        // canAdminister() call site has migrated onto Authority (docs/plans/active/AUTH-ROLES-PLAN.md
        // §3.6's staging rule).
        User user = user(new Membership(warehouseA, Role.VIEWER));

        VisibilityScope scope = resolver.scopeFor(user);

        assertEquals(VisibilityScope.Kind.GROUPS, scope.kind());
        assertEquals(Set.of(warehouseA, teamA1), scope.groups());
    }

    @Test
    void authorityForAViewerPairsGroupsScopeWithNoCapabilities() {
        // A VIEWER's wide scope is read-only in effect: mayManageOrg()/mayManageFleet()/
        // mayAdminister() each additionally require a capability RoleAuthority never grants VIEWER.
        User user = user(new Membership(warehouseA, Role.VIEWER));

        Authority authority = resolver.authorityFor(user);

        assertEquals(VisibilityScope.Kind.GROUPS, authority.scope().kind());
        assertTrue(authority.capabilities().isEmpty());
    }

    @Test
    void userWithNoMembershipsAndNoAssignmentsSeesNothing() {
        VisibilityScope scope = resolver.scopeFor(user());

        assertEquals(VisibilityScope.Kind.ASSIGNED_ASSETS, scope.kind());
        assertTrue(scope.assignedAssets().isEmpty());
    }

    @Test
    void aCycleInTheGroupTreeDoesNotHang() {
        GroupId a = GroupId.random();
        GroupId b = GroupId.random();
        groupRepository.save(new Group(a, "a", b));
        groupRepository.save(new Group(b, "b", a)); // a<->b cycle
        User manager = user(new Membership(a, Role.MANAGER));

        assertTimeoutPreemptively(java.time.Duration.ofSeconds(2), () -> {
            VisibilityScope scope = resolver.scopeFor(manager);
            assertEquals(Set.of(a, b), scope.groups());
        });
    }

    @Test
    void constructorRejectsNullCollaborators() {
        assertThrows(NullPointerException.class,
                () -> new DefaultScopeResolver(null, assignmentRepository));
        assertThrows(NullPointerException.class,
                () -> new DefaultScopeResolver(groupRepository, null));
    }

    // --- authorityFor(User) (docs/plans/active/AUTH-ROLES-PLAN.md §3.3, wave B1) -----------------

    @Test
    void authorityForAnAdminPairsUnboundedScopeWithEveryCapability() {
        User user = user(new Membership(warehouseA, Role.ADMIN));

        Authority authority = resolver.authorityFor(user);

        assertTrue(authority.scope().isUnbounded());
        assertEquals(Set.of(Capability.values()), authority.capabilities());
    }

    @Test
    void authorityForAManagerPairsGroupsScopeWithEveryCapability() {
        User user = user(new Membership(warehouseA, Role.MANAGER));

        Authority authority = resolver.authorityFor(user);

        assertEquals(resolver.scopeFor(user), authority.scope());
        assertEquals(Set.of(Capability.values()), authority.capabilities());
    }

    @Test
    void authorityForAPilotPairsAssignedAssetsScopeWithPayloadAndFlightOnly() {
        User user = user(new Membership(warehouseA, Role.PILOT));

        Authority authority = resolver.authorityFor(user);

        assertEquals(VisibilityScope.Kind.ASSIGNED_ASSETS, authority.scope().kind());
        assertEquals(Set.of(Capability.OPERATE_PAYLOAD,
                Capability.COMMAND_FLIGHT), authority.capabilities());
    }

    @Test
    void authorityForAUserWithNoMembershipsHoldsNoCapabilities() {
        Authority authority = resolver.authorityFor(user());

        assertEquals(VisibilityScope.Kind.ASSIGNED_ASSETS, authority.scope().kind());
        assertTrue(authority.capabilities().isEmpty());
    }

    @Test
    void authorityForUsesTheSameTopRolePrecedenceAsScopeFor() {
        // A PILOT+ADMIN pair: scopeFor already picks ADMIN (unbounded) via topRole precedence;
        // authorityFor must derive its capabilities from that same winning role, not the pilot one.
        User user = user(new Membership(warehouseA, Role.PILOT), new Membership(warehouseB, Role.ADMIN));

        Authority authority = resolver.authorityFor(user);

        assertTrue(authority.scope().isUnbounded());
        assertEquals(Set.of(Capability.values()), authority.capabilities());
    }

    /** In-memory {@link GroupRepositoryPort}. */
    private static final class FakeGroupRepositoryPort implements GroupRepositoryPort {
        private final Map<GroupId, Group> groups = new ConcurrentHashMap<>();

        @Override
        public Optional<Group> findById(GroupId id) {
            return Optional.ofNullable(groups.get(id));
        }

        @Override
        public List<Group> findAll() {
            return List.copyOf(groups.values());
        }

        @Override
        public Group save(Group group) {
            groups.put(group.id(), group);
            return group;
        }
    }

    /** In-memory {@link AssignmentRepositoryPort}, keyed by (pilot, asset) with each link's seat. */
    private static final class FakeAssignmentRepositoryPort implements AssignmentRepositoryPort {
        private final Map<UserId, Map<AssetId, AssignmentRole>> byPilot = new HashMap<>();

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
}
