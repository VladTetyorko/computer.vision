package com.drones.vision.application;

import com.drones.vision.domain.model.Group;

import java.util.List;

/**
 * Creates and lists {@link Group}s (docs/U-AUTH-PLAN.md, wave 2).
 *
 * <p><strong>Slice 1 scope:</strong> only {@code create}/{@code list} exist. Full tree/hierarchy
 * management (rename, re-parent, delete-with-children, a dedicated {@code tree()} read model) is
 * slice 2 (docs/U-AUTH-PLAN.md, "Explicitly deferred to slice 2") — {@link #list()}'s flat,
 * sorted list is enough for slice 1's admin screens, which only need to name a group when
 * creating a user or a child group.
 */
public interface GroupService {

    /**
     * Creates a new group.
     *
     * @param spec the new group's shape
     * @return the created, persisted group
     * @throws java.util.NoSuchElementException if {@link GroupSpec#parentGroupId()} is non-null
     *                                           and unknown
     */
    Group create(GroupSpec spec);

    /**
     * Lists all groups, sorted by name (case-insensitive) for a stable UI order.
     *
     * @return every group
     */
    List<Group> list();
}
