package com.drones.vision.app.devsupport;

import com.drones.vision.domain.model.Group;
import com.drones.vision.domain.model.GroupId;
import com.drones.vision.domain.port.out.GroupRepositoryPort;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory {@link GroupRepositoryPort}: dev fallback with no durability across restarts
 * (docs/plans/done/U-AUTH-PLAN.md, wave 3).
 *
 * <p>Replaced by {@code adapter-persistence}'s {@code JpaGroupRepository} when {@code
 * vision.persistence.enabled=true}. {@link #save} is a plain upsert-by-id ({@code Map#put}),
 * matching the JPA impl's merge-by-id.
 */
public final class InMemoryGroupRepository implements GroupRepositoryPort {

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
