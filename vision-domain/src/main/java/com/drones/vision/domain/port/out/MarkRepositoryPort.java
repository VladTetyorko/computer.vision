package com.drones.vision.domain.port.out;

import com.drones.vision.domain.model.Mark;
import com.drones.vision.domain.model.MarkId;

import java.util.List;
import java.util.Optional;

/**
 * Driven port: persist and retrieve {@link Mark}s (docs/plans/done/TACTICAL-MARKS-PLAN.md §1) — the same
 * minimal upsert/find/delete shape as {@link GeofenceRepositoryPort}, {@code Mark}'s own template.
 *
 * <p>Unlike {@link GeofenceRepositoryPort} (global reference data), a {@link Mark} carries
 * {@code ownership}, but this port still has no ownership- or group-aware query: {@link
 * #findAll()} always returns every mark, and scope filtering over that snapshot is the
 * application layer's job (docs/plans/done/TACTICAL-MARKS-PLAN.md §2), the same "no referential integrity /
 * no scope filtering baked into the repository" convention every other port here follows.
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li>{@link #save(Mark)} upserts by {@link Mark#id()}: an id seen before is replaced in place,
 *       a new id is added.</li>
 *   <li>{@link #findById(MarkId)} returns {@link Optional#empty()}, never {@code null}, when no
 *       mark with that id exists.</li>
 *   <li>{@link #findAll()} returns a snapshot; the returned list is not a live view of the
 *       store.</li>
 *   <li>{@link #deleteById(MarkId)} is idempotent: deleting a non-existent id is a no-op, not an
 *       error.</li>
 * </ul>
 *
 * <h2>Threading</h2>
 * Implementations must be safe for concurrent use — mark CRUD (control-plane requests) and mark
 * reads (a driving adapter refreshing the shared operational picture) may happen concurrently.
 */
public interface MarkRepositoryPort {

    /**
     * Inserts or updates a mark.
     *
     * @param mark the mark to persist
     * @return the persisted mark
     */
    Mark save(Mark mark);

    /**
     * Finds a mark by id.
     *
     * @param id the mark id
     * @return the mark, or {@link Optional#empty()} if none exists
     */
    Optional<Mark> findById(MarkId id);

    /**
     * Lists all marks.
     *
     * @return an immutable snapshot of all marks
     */
    List<Mark> findAll();

    /**
     * Deletes a mark by id. Idempotent.
     *
     * @param id the mark id to delete
     */
    void deleteById(MarkId id);
}
