package com.drones.vision.application;

import com.drones.vision.domain.model.AssetId;
import com.drones.vision.domain.model.Group;
import com.drones.vision.domain.model.GroupId;
import com.drones.vision.domain.model.Membership;
import com.drones.vision.domain.model.Role;
import com.drones.vision.domain.model.User;
import com.drones.vision.domain.model.UserId;
import com.drones.vision.domain.port.out.AssignmentRepositoryPort;
import com.drones.vision.domain.port.out.GroupRepositoryPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
        return new User(UserId.random(), "u", "U", "u@x", "hash", true, List.of(memberships));
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
        assignmentRepository.assign(user.id(), assigned);

        VisibilityScope scope = resolver.scopeFor(user);

        assertEquals(VisibilityScope.Kind.ASSIGNED_ASSETS, scope.kind());
        assertEquals(Set.of(assigned), scope.assignedAssets());
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

    /** In-memory {@link AssignmentRepositoryPort}. */
    private static final class FakeAssignmentRepositoryPort implements AssignmentRepositoryPort {
        private final Map<UserId, Set<AssetId>> byPilot = new HashMap<>();

        @Override
        public void assign(UserId pilot, AssetId asset) {
            byPilot.computeIfAbsent(pilot, k -> new HashSet<>()).add(asset);
        }

        @Override
        public void unassign(UserId pilot, AssetId asset) {
            byPilot.getOrDefault(pilot, Set.of()).remove(asset);
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
