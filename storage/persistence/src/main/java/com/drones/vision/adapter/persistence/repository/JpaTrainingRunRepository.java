package com.drones.vision.adapter.persistence.repository;

import com.drones.vision.adapter.persistence.config.JpaOperations;
import com.drones.vision.adapter.persistence.entity.TrainingRunEntity;
import com.drones.vision.adapter.persistence.mapper.TrainingRunMapper;
import com.drones.vision.learning.domain.model.TrainingRunId;
import com.drones.vision.learning.domain.model.TrainingRunRecord;
import com.drones.vision.learning.domain.port.TrainingRunRepositoryPort;

import jakarta.persistence.EntityManagerFactory;

import java.util.List;
import java.util.Optional;

/**
 * {@link TrainingRunRepositoryPort} backed by Postgres via plain JPA (see {@link JpaOperations}) —
 * docs/plans/active/CV-SETTINGS-PLAN.md §3.3, §5.3, CV-SETTINGS wave W3 (fixes H7: "training
 * metrics evaporate").
 *
 * <p>{@link #save} is a {@code merge} by {@code runId} — a run is written once when it starts and
 * again as it progresses/finishes, replacing the prior row in place each time, matching the port's
 * own contract. {@link #findAll(int)} orders newest-first by {@code started_at}, the same column
 * {@code idx_cv_training_runs_started_at} (V30) indexes for it.
 */
public final class JpaTrainingRunRepository implements TrainingRunRepositoryPort {

    private final JpaOperations jpa;

    public JpaTrainingRunRepository(EntityManagerFactory entityManagerFactory) {
        this.jpa = new JpaOperations(entityManagerFactory);
    }

    @Override
    public Optional<TrainingRunRecord> findById(TrainingRunId id) {
        return jpa.read(em -> Optional.ofNullable(em.find(TrainingRunEntity.class, id.value())))
                .map(TrainingRunMapper::toDomain);
    }

    @Override
    public List<TrainingRunRecord> findAll(int limit) {
        return jpa.read(em -> em.createQuery(
                        "select r from TrainingRunEntity r order by r.startedAt desc", TrainingRunEntity.class)
                        .setMaxResults(limit)
                        .getResultList())
                .stream()
                .map(TrainingRunMapper::toDomain)
                .toList();
    }

    @Override
    public TrainingRunRecord save(TrainingRunRecord run) {
        TrainingRunEntity saved = jpa.write(em -> em.merge(TrainingRunMapper.toEntity(run)));
        return TrainingRunMapper.toDomain(saved);
    }
}
