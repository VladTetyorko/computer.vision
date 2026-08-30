package com.drones.vision.learning.domain.port;

import com.drones.vision.learning.domain.model.TrainingRunId;
import com.drones.vision.learning.domain.model.TrainingRunRecord;

import java.util.List;
import java.util.Optional;

/**
 * Driven port: persist and query {@link TrainingRunRecord}s (docs/plans/active/CV-SETTINGS-PLAN.md
 * §3.3, §5.3 {@code cv_training_runs}) — the durable record H7 found missing.
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li>{@link #save(TrainingRunRecord)} upserts by {@link TrainingRunRecord#runId()}: a run is
 *       written once when it starts and again as it progresses/finishes, replacing the prior row in
 *       place each time.</li>
 *   <li>{@link #findById(TrainingRunId)} returns {@link Optional#empty()}, never {@code null}, when
 *       no run with that id exists.</li>
 *   <li>{@link #findAll(int)} lists runs newest-first by {@link TrainingRunRecord#startedAt()},
 *       bounded to {@code limit}.</li>
 * </ul>
 *
 * <h2>Threading</h2>
 * Implementations must be safe for concurrent use — a run's own progress updates and a run-history
 * read may happen concurrently, and distinct runs update independently.
 */
public interface TrainingRunRepositoryPort {

    /**
     * Finds a training run by id.
     *
     * @param id the run id
     * @return the run, or {@link Optional#empty()} if none exists
     */
    Optional<TrainingRunRecord> findById(TrainingRunId id);

    /**
     * Lists training runs newest-first by start time.
     *
     * @param limit maximum number of runs to return; must be positive
     * @return an immutable snapshot, most recently started first
     */
    List<TrainingRunRecord> findAll(int limit);

    /**
     * Inserts or updates a training run.
     *
     * @param run the run to persist
     * @return the persisted run
     */
    TrainingRunRecord save(TrainingRunRecord run);
}
