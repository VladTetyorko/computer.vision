package com.drones.vision.application;

import com.drones.vision.domain.model.Group;
import com.drones.vision.domain.model.GroupId;
import com.drones.vision.domain.port.out.GroupRepositoryPort;

import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;

/**
 * The one implementation of {@link GroupService}.
 *
 * <h2>Threading</h2>
 * Holds no mutable state — safe to call concurrently.
 */
public final class DefaultGroupService implements GroupService {

    private final GroupRepositoryPort groupRepository;

    public DefaultGroupService(GroupRepositoryPort groupRepository) {
        this.groupRepository = Objects.requireNonNull(groupRepository, "groupRepository must not be null");
    }

    @Override
    public Group create(GroupSpec spec) {
        Objects.requireNonNull(spec, "spec must not be null");
        GroupId parentGroupId = spec.parentGroupId();
        if (parentGroupId != null && groupRepository.findById(parentGroupId).isEmpty()) {
            throw new NoSuchElementException("unknown parent group: " + parentGroupId);
        }
        Group group = new Group(GroupId.random(), spec.name(), parentGroupId);
        return groupRepository.save(group);
    }

    @Override
    public List<Group> list() {
        return groupRepository.findAll().stream()
                .sorted(Comparator.comparing(Group::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }
}
