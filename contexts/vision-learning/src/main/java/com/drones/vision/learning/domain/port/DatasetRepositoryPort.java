package com.drones.vision.learning.domain.port;

import com.drones.vision.learning.domain.model.Dataset;
import com.drones.vision.learning.domain.model.DatasetId;

import java.util.List;
import java.util.Optional;

/**
 * Driven port: persist and retrieve {@link Dataset}s (docs/plans/done/CV-TRAINING-PLAN.md §1).
 *
 * <p>A dataset carries {@code ownership}, but this port has no ownership- or group-aware query:
 * {@link #findAll()} always returns every dataset, and group-scoped visibility over that snapshot
 * is the application layer's job (docs/plans/done/CV-TRAINING-PLAN.md §2), the same "no scope filtering baked
 * into the repository" convention every other port in this module follows (see {@link
 * MarkRepositoryPort}).
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li>{@link #save(Dataset)} upserts by {@link Dataset#id()}: an id seen before is replaced in
 *       place, a new id is added.</li>
 *   <li>{@link #findById(DatasetId)} returns {@link Optional#empty()}, never {@code null}, when no
 *       dataset with that id exists.</li>
 *   <li>{@link #findAll()} returns a snapshot; the returned list is not a live view of the
 *       store.</li>
 *   <li>{@link #delete(DatasetId)} is idempotent: deleting a non-existent id is a no-op, not an
 *       error. It does not cascade to the dataset's samples or images — that is left to the
 *       caller, consistent with this module's "no referential integrity between repositories"
 *       convention.</li>
 * </ul>
 *
 * <h2>Threading</h2>
 * Implementations must be safe for concurrent use — dataset CRUD (control-plane requests) and
 * dataset reads may happen concurrently.
 */
public interface DatasetRepositoryPort {

    /**
     * Inserts or updates a dataset.
     *
     * @param dataset the dataset to persist
     * @return the persisted dataset
     */
    Dataset save(Dataset dataset);

    /**
     * Finds a dataset by id.
     *
     * @param id the dataset id
     * @return the dataset, or {@link Optional#empty()} if none exists
     */
    Optional<Dataset> findById(DatasetId id);

    /**
     * Lists all datasets.
     *
     * @return an immutable snapshot of all datasets; scope-filtering is the caller's job
     */
    List<Dataset> findAll();

    /**
     * Deletes a dataset by id. Idempotent. Does not cascade to its samples/images.
     *
     * @param id the dataset id to delete
     */
    void delete(DatasetId id);
}
