package com.drones.vision.application.scope;

import com.drones.vision.domain.model.Group;
import com.drones.vision.domain.model.GroupId;
import com.drones.vision.domain.model.Membership;
import com.drones.vision.domain.model.Role;
import com.drones.vision.domain.model.User;
import com.drones.vision.domain.port.out.AssignmentRepositoryPort;
import com.drones.vision.domain.port.out.GroupRepositoryPort;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The one implementation of {@link ScopeResolver}.
 *
 * <h2>Subtree expansion</h2>
 * For a MANAGER, the scope is the union of the subtree (self + all descendants via
 * {@link Group#parentGroupId()}) of each group they manage. The tree is read once per call from
 * {@link GroupRepositoryPort#findAll()} into a parent&rarr;children map, then a breadth-first walk
 * from each manager group collects the reachable groups. A {@code visited} set both dedupes
 * overlapping subtrees and guards against a malformed cycle in the stored tree — a group already
 * visited is never re-enqueued, so the walk always terminates.
 *
 * <h2>Threading</h2>
 * Holds no mutable state — every resolution is answered fresh from the injected ports.
 */
public final class DefaultScopeResolver implements ScopeResolver {

    private final GroupRepositoryPort groupRepository;
    private final AssignmentRepositoryPort assignmentRepository;

    public DefaultScopeResolver(GroupRepositoryPort groupRepository,
                                 AssignmentRepositoryPort assignmentRepository) {
        this.groupRepository = Objects.requireNonNull(groupRepository, "groupRepository must not be null");
        this.assignmentRepository =
                Objects.requireNonNull(assignmentRepository, "assignmentRepository must not be null");
    }

    @Override
    public VisibilityScope scopeFor(User user) {
        Objects.requireNonNull(user, "user must not be null");

        boolean admin = user.memberships().stream().anyMatch(m -> m.role() == Role.ADMIN);
        if (admin) {
            return VisibilityScope.unbounded();
        }

        Set<GroupId> managerGroups = user.memberships().stream()
                .filter(m -> m.role() == Role.MANAGER)
                .map(Membership::groupId)
                .collect(Collectors.toSet());
        if (!managerGroups.isEmpty()) {
            return VisibilityScope.groups(subtreeOf(managerGroups));
        }

        // PILOT-only, or no membership at all: only explicitly assigned assets. A user with no
        // assignments gets the empty set, which includes nothing — they see nothing until assigned.
        return VisibilityScope.assignedAssets(assignmentRepository.assetsForPilot(user.id()));
    }

    /** The union of the self+descendants subtree of each root group, cycle-safe. */
    private Set<GroupId> subtreeOf(Set<GroupId> roots) {
        Map<GroupId, List<GroupId>> children = new HashMap<>();
        for (Group group : groupRepository.findAll()) {
            if (group.parentGroupId() != null) {
                children.computeIfAbsent(group.parentGroupId(), key -> new ArrayList<>()).add(group.id());
            }
        }

        Set<GroupId> visited = new LinkedHashSet<>();
        Deque<GroupId> queue = new ArrayDeque<>(roots);
        while (!queue.isEmpty()) {
            GroupId group = queue.poll();
            if (!visited.add(group)) {
                continue; // already seen: dedupes overlapping subtrees and breaks any cycle
            }
            for (GroupId child : children.getOrDefault(group, List.of())) {
                if (!visited.contains(child)) {
                    queue.add(child);
                }
            }
        }
        return visited;
    }
}
