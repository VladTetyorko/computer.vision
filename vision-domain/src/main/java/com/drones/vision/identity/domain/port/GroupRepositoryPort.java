package com.drones.vision.identity.domain.port;

import com.drones.vision.identity.domain.model.Group;
import com.drones.vision.kernel.GroupId;

import java.util.List;
import java.util.Optional;

/**
 * Driven port: persist and retrieve {@link Group}s (docs/plans/done/U-AUTH-PLAN.md, wave 1).
 *
 * <p>Slice 1 only stores and lists the group tree ({@code parentGroupId} link); subtree
 * visibility scoping is built on top of this port in a later slice, not part of it.
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li>{@link #findById(GroupId)} returns {@link Optional#empty()}, never {@code null}, when no
 *       group with that id exists.</li>
 *   <li>{@link #findAll()} returns a snapshot; the returned list is not a live view of the
 *       store.</li>
 *   <li>{@link #save(Group)} upserts by {@link GroupId} and returns the persisted group.</li>
 * </ul>
 *
 * <h2>Threading</h2>
 * Implementations must be safe for concurrent use — multiple control-plane operations may
 * read/write groups concurrently, and no caller assumes exclusive access.
 */
public interface GroupRepositoryPort {

    /**
     * Finds a group by id.
     *
     * @param id the group id
     * @return the group, or {@link Optional#empty()} if none exists
     */
    Optional<Group> findById(GroupId id);

    /**
     * Lists all groups.
     *
     * @return an immutable snapshot of all groups
     */
    List<Group> findAll();

    /**
     * Inserts or updates a group.
     *
     * @param group the group to persist
     * @return the persisted group
     */
    Group save(Group group);
}
