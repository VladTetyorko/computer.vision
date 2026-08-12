package com.drones.vision.identity.application;

import com.drones.vision.identity.domain.model.Group;
import com.drones.vision.kernel.GroupId;
import com.drones.vision.identity.domain.port.GroupRepositoryPort;

import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import com.drones.vision.platform.AccessDeniedException;
import com.drones.vision.platform.VisibilityScope;

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
    public Group create(GroupSpec spec, VisibilityScope acting) {
        Objects.requireNonNull(spec, "spec must not be null");
        Objects.requireNonNull(acting, "acting must not be null");
        if (!acting.canManageOrg()) {
            throw new AccessDeniedException("not permitted to create groups");
        }
        GroupId parentGroupId = spec.parentGroupId();
        if (parentGroupId == null) {
            if (!acting.isUnbounded()) {
                throw new AccessDeniedException("only an administrator may create a root group");
            }
        } else {
            if (!acting.includesGroup(parentGroupId)) {
                throw new AccessDeniedException("cannot create a group under a parent outside your scope");
            }
            if (groupRepository.findById(parentGroupId).isEmpty()) {
                throw new NoSuchElementException("unknown parent group: " + parentGroupId);
            }
        }
        Group group = new Group(GroupId.random(), spec.name(), parentGroupId);
        return groupRepository.save(group);
    }

    @Override
    public List<Group> list(VisibilityScope acting) {
        Objects.requireNonNull(acting, "acting must not be null");
        List<Group> sorted = groupRepository.findAll().stream()
                .sorted(Comparator.comparing(Group::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
        if (acting.isUnbounded()) {
            return sorted;
        }
        // A GROUPS scope keeps its own subtree; an ASSIGNED_ASSETS scope's includesGroup is always
        // false, correctly yielding an empty list for a pilot.
        return sorted.stream()
                .filter(group -> acting.includesGroup(group.id()))
                .toList();
    }
}
