package com.drones.vision.learning.domain.port;

import com.drones.vision.learning.domain.model.CvModelRecord;
import com.drones.vision.learning.domain.model.ModelStatus;

import java.util.List;
import java.util.Optional;

/**
 * Driven port: persist and query the CV model catalogue (docs/plans/active/CV-SETTINGS-PLAN.md
 * §3.2, §5.3 {@code cv_models}).
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li>{@link #save(CvModelRecord)} upserts by the composite key ({@link CvModelRecord#modelId()},
 *       {@link CvModelRecord#version()}): a pair seen before is replaced in place, a new pair is
 *       added.</li>
 *   <li>{@link #findByIdAndVersion(String, String)} returns {@link Optional#empty()}, never {@code
 *       null}, when no row matches.</li>
 *   <li>{@link #findAll()} returns a snapshot of every row, every status; scope/status filtering is
 *       the application layer's job.</li>
 *   <li>{@link #findLive()} returns the one row currently {@link ModelStatus#LIVE}, or {@link
 *       Optional#empty()} if none is. <b>This port does not itself enforce "exactly one LIVE"</b> —
 *       that invariant is the service's job (demoting the previous LIVE row before/atomically with
 *       promoting a new one). This method exists so callers never have to filter {@link #findAll()}
 *       themselves to answer "which model is live".</li>
 * </ul>
 *
 * <h2>Threading</h2>
 * Implementations must be safe for concurrent use — promotion (an infrequent control-plane
 * operation) and picker/coverage reads may happen concurrently.
 */
public interface CvModelRepositoryPort {

    /**
     * Finds a catalogue row by its composite key.
     *
     * @param modelId the model id
     * @param version the model version
     * @return the row, or {@link Optional#empty()} if none exists
     */
    Optional<CvModelRecord> findByIdAndVersion(String modelId, String version);

    /**
     * Lists every catalogue row, every status.
     *
     * @return an immutable snapshot of every row
     */
    List<CvModelRecord> findAll();

    /**
     * Finds the one row currently {@link ModelStatus#LIVE}.
     *
     * @return the LIVE row, or {@link Optional#empty()} if none is currently LIVE
     */
    Optional<CvModelRecord> findLive();

    /**
     * Inserts or updates a catalogue row.
     *
     * @param model the row to persist
     * @return the persisted row
     */
    CvModelRecord save(CvModelRecord model);
}
