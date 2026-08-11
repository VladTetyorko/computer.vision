package com.drones.vision.domain.port.out;

import com.drones.vision.domain.model.SampleImage;
import com.drones.vision.domain.model.TrainingSampleId;

import java.util.Optional;

/**
 * Driven port: stores and retrieves one {@link SampleImage} per {@link TrainingSampleId}
 * (docs/plans/done/CV-TRAINING-PLAN.md §1/§C) — the {@code AssetImageRepositoryPort} shape, verbatim, reused
 * for training-sample frames rather than asset photos.
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li>{@link #save} is an upsert — a second call for the same {@link TrainingSampleId} replaces
 *       the first, it never adds a second image.</li>
 *   <li>{@link #findById} returns {@link Optional#empty()} for a sample that has never had an
 *       image saved (or whose image was since deleted) — never an error, and never distinguishes
 *       that case from "this sample id doesn't even exist" (this port has no dependency on {@code
 *       TrainingSampleRepositoryPort} — same "no referential integrity between repositories"
 *       convention as {@link AssetImageRepositoryPort}).</li>
 *   <li>{@link #delete} is idempotent — deleting for a sample with no stored image is a no-op, not
 *       an error.</li>
 * </ul>
 *
 * <h2>Threading</h2>
 * Every method must be safe to call concurrently for different {@link TrainingSampleId}s.
 */
public interface SampleImageStorePort {

    /**
     * Stores {@code image} for {@code id}, replacing whatever was stored before.
     *
     * @param id    the training sample the image belongs to
     * @param image the image to store
     */
    void save(TrainingSampleId id, SampleImage image);

    /**
     * @param id the training sample to look up
     * @return the sample's stored image, or {@link Optional#empty()} if none
     */
    Optional<SampleImage> findById(TrainingSampleId id);

    /**
     * Removes {@code id}'s stored image, if any. Idempotent.
     *
     * @param id the training sample whose image to remove
     */
    void delete(TrainingSampleId id);
}
