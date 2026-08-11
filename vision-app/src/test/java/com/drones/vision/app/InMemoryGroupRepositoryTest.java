package com.drones.vision.app;

import com.drones.vision.app.devsupport.InMemoryGroupRepository;
import com.drones.vision.identity.domain.model.Group;
import com.drones.vision.kernel.GroupId;
import com.drones.vision.identity.domain.port.GroupRepositoryPort;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link InMemoryGroupRepository}: store/list + upsert (docs/plans/done/U-AUTH-PLAN.md, wave 3). */
class InMemoryGroupRepositoryTest {

    private final GroupRepositoryPort repository = new InMemoryGroupRepository();

    @Test
    void savedGroupsRoundTripAndUnknownIsEmpty() {
        Group root = new Group(GroupId.random(), "Root", null);
        Group child = new Group(GroupId.random(), "Child", root.id());
        repository.save(root);
        repository.save(child);

        assertEquals(root, repository.findById(root.id()).orElseThrow());
        assertEquals(root.id(), repository.findById(child.id()).orElseThrow().parentGroupId());
        assertTrue(repository.findById(GroupId.random()).isEmpty());
        assertEquals(2, repository.findAll().size());
    }

    @Test
    void saveUpsertsById() {
        GroupId id = GroupId.random();
        repository.save(new Group(id, "Original", null));
        repository.save(new Group(id, "Renamed", null));

        assertEquals("Renamed", repository.findById(id).orElseThrow().name());
        assertEquals(1, repository.findAll().size());
    }
}
