package com.drones.vision.application;

import com.drones.vision.domain.model.Group;
import com.drones.vision.domain.model.GroupId;
import com.drones.vision.domain.port.out.GroupRepositoryPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DefaultGroupServiceTest {

    private FakeGroupRepositoryPort groupRepository;
    private GroupService service;

    @BeforeEach
    void setUp() {
        groupRepository = new FakeGroupRepositoryPort();
        service = new DefaultGroupService(groupRepository);
    }

    @Test
    void createRootGroupWithNoParent() {
        Group created = service.create(new GroupSpec("hq", null));

        assertEquals("hq", created.name());
        assertNull(created.parentGroupId());
        assertEquals(Optional.of(created), groupRepository.findById(created.id()));
    }

    @Test
    void createChildGroupWithAnExistingParent() {
        Group parent = service.create(new GroupSpec("hq", null));

        Group child = service.create(new GroupSpec("warehouse-1", parent.id()));

        assertEquals(parent.id(), child.parentGroupId());
    }

    @Test
    void createChildWithAnUnknownParentThrows() {
        GroupId unknownParent = GroupId.random();

        assertThrows(NoSuchElementException.class, () -> service.create(new GroupSpec("orphan", unknownParent)));
    }

    @Test
    void listIsSortedByNameCaseInsensitive() {
        service.create(new GroupSpec("Charlie", null));
        service.create(new GroupSpec("alpha", null));
        service.create(new GroupSpec("Bravo", null));

        List<String> names = service.list().stream().map(Group::name).toList();

        assertEquals(List.of("alpha", "Bravo", "Charlie"), names);
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
