package com.drones.vision.application.identity;

import com.drones.vision.identity.domain.model.Group;
import com.drones.vision.kernel.GroupId;
import com.drones.vision.identity.domain.port.GroupRepositoryPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
import com.drones.vision.application.scope.AccessDeniedException;
import com.drones.vision.application.scope.VisibilityScope;

class DefaultGroupServiceTest {

    private FakeGroupRepositoryPort groupRepository;
    private GroupService service;

    @BeforeEach
    void setUp() {
        groupRepository = new FakeGroupRepositoryPort();
        service = new DefaultGroupService(groupRepository);
    }

    private static final VisibilityScope ADMIN = VisibilityScope.unbounded();

    @Test
    void createRootGroupWithNoParent() {
        Group created = service.create(new GroupSpec("hq", null), ADMIN);

        assertEquals("hq", created.name());
        assertNull(created.parentGroupId());
        assertEquals(Optional.of(created), groupRepository.findById(created.id()));
    }

    @Test
    void createChildGroupWithAnExistingParent() {
        Group parent = service.create(new GroupSpec("hq", null), ADMIN);

        Group child = service.create(new GroupSpec("warehouse-1", parent.id()), ADMIN);

        assertEquals(parent.id(), child.parentGroupId());
    }

    @Test
    void createChildWithAnUnknownParentThrows() {
        GroupId unknownParent = GroupId.random();

        assertThrows(NoSuchElementException.class,
                () -> service.create(new GroupSpec("orphan", unknownParent), ADMIN));
    }

    @Test
    void listIsSortedByNameCaseInsensitive() {
        service.create(new GroupSpec("Charlie", null), ADMIN);
        service.create(new GroupSpec("alpha", null), ADMIN);
        service.create(new GroupSpec("Bravo", null), ADMIN);

        List<String> names = service.list(ADMIN).stream().map(Group::name).toList();

        assertEquals(List.of("alpha", "Bravo", "Charlie"), names);
    }

    // --- management gates (docs/plans/done/U-SCOPE-PLAN.md, U-e slice 2 cleanup) ---

    @Test
    void managerCreatesAChildUnderTheirOwnGroupButNotARoot() {
        Group parent = service.create(new GroupSpec("division", null), ADMIN);
        VisibilityScope manager = VisibilityScope.groups(Set.of(parent.id()));

        Group child = service.create(new GroupSpec("team-a", parent.id()), manager);
        assertEquals(parent.id(), child.parentGroupId());

        assertThrows(AccessDeniedException.class, () -> service.create(new GroupSpec("new-root", null), manager));
    }

    @Test
    void managerCannotCreateAChildUnderAForeignParent() {
        Group foreign = service.create(new GroupSpec("foreign", null), ADMIN);
        VisibilityScope manager = VisibilityScope.groups(Set.of(GroupId.random()));

        assertThrows(AccessDeniedException.class,
                () -> service.create(new GroupSpec("team", foreign.id()), manager));
    }

    @Test
    void pilotScopeCannotCreateAnyGroup() {
        VisibilityScope pilot = VisibilityScope.assignedAssets(Set.of());

        assertThrows(AccessDeniedException.class, () -> service.create(new GroupSpec("nope", null), pilot));
    }

    @Test
    void listFiltersToTheScopeAndIsEmptyForAPilot() {
        Group mine = service.create(new GroupSpec("mine", null), ADMIN);
        service.create(new GroupSpec("theirs", null), ADMIN);

        VisibilityScope manager = VisibilityScope.groups(Set.of(mine.id()));
        List<Group> visible = service.list(manager);
        assertEquals(1, visible.size());
        assertEquals("mine", visible.get(0).name());

        assertEquals(2, service.list(ADMIN).size());
        assertTrue(service.list(VisibilityScope.assignedAssets(Set.of())).isEmpty());
    }

    @Test
    void constructorRejectsNullCollaborator() {
        assertThrows(NullPointerException.class, () -> new DefaultGroupService(null));
    }

    /** In-memory {@link GroupRepositoryPort}, keyed by id. */
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
}
