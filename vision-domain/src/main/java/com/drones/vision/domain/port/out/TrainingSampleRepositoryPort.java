package com.drones.vision.domain.port.out;

import com.drones.vision.domain.model.DatasetId;
import com.drones.vision.domain.model.SampleStatus;
import com.drones.vision.domain.model.TrainingSample;
import com.drones.vision.domain.model.TrainingSampleId;

import java.util.Optional;
import java.util.List;

/**
 * Driven port: persist and query {@link TrainingSample}s (docs/plans/done/CV-TRAINING-PLAN.md §1).
 *
 * <p>A sample mutates over its own review lifecycle (annotations and status evolve as the operator
 * labels it), so {@link #save} is a genuine upsert keyed by the sample's own id, not an append —
 * the same "mutates in place, not append-only" shape as {@code DetectionEventRepositoryPort},
 * unlike {@code DetectionRepositoryPort}'s immutable historical rows.
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li>{@link #save(TrainingSample)} upserts by {@link TrainingSample#id()}: an id seen before is
 *       replaced in place (e.g. a capture's PENDING row moving to LABELED/DISCARDED), a new id is
 *       added.</li>
 *   <li>{@link #findById(TrainingSampleId)} returns {@link Optional#empty()}, never {@code null},
 *       when no sample with that id exists.</li>
 *   <li>{@link #findByDataset(DatasetId, SampleStatus, int)} lists one dataset's samples, bounded
 *       to {@code limit}; {@code statusOrNull} is nullable — {@code null} means every status,
 *       non-null restricts to exactly that one. Order is implementation-defined. An unknown/
 *       sampleless dataset yields an empty list, not an error.</li>
 *   <li>{@link #countByDataset(DatasetId, SampleStatus)} answers the same filter as {@link
 *       #findByDataset} without loading any sample rows — the cheap count a dataset summary (e.g.
 *       {@code sampleCounts} per status) uses.</li>
 *   <li>{@link #delete(TrainingSampleId)} is idempotent: deleting a non-existent id is a no-op, not
 *       an error. It does not cascade to the sample's stored image — that is left to the caller,
 *       same "no referential integrity between repositories" convention as every other port
 *       here.</li>
 * </ul>
 *
 * <h2>Threading</h2>
 * Implementations must be safe for concurrent use — capture/label writes and dataset-summary/
 * labeling-queue reads may happen concurrently.
 */
public interface TrainingSampleRepositoryPort {

    /**
     * Inserts or updates a training sample.
     *
     * @param sample the sample to persist
     * @return the persisted sample
     */
    TrainingSample save(TrainingSample sample);

    /**
     * Finds a training sample by id.
     *
     * @param id the sample id
     * @return the sample, or {@link Optional#empty()} if none exists
     */
    Optional<TrainingSample> findById(TrainingSampleId id);

    /**
     * Lists one dataset's samples, optionally restricted to one status.
     *
     * @param datasetId    the dataset to list samples for
     * @param statusOrNull restrict to this status only, or {@code null} for every status
     * @param limit        maximum number of samples to return; must be positive
     * @return an immutable snapshot; empty if the dataset has no matching samples
     */
    List<TrainingSample> findByDataset(DatasetId datasetId, SampleStatus statusOrNull, int limit);

    /**
     * Counts one dataset's samples, optionally restricted to one status, without loading them.
     *
     * @param datasetId    the dataset to count samples for
     * @param statusOrNull restrict to this status only, or {@code null} for every status
     * @return the matching sample count
     */
    int countByDataset(DatasetId datasetId, SampleStatus statusOrNull);

    /**
     * Deletes a training sample by id. Idempotent. Does not cascade to its stored image.
     *
     * @param id the sample id to delete
     */
    void delete(TrainingSampleId id);
}
