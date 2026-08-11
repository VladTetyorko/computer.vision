package com.drones.vision.adapter.persistence.repository;

import com.drones.vision.adapter.persistence.config.JpaOperations;
import com.drones.vision.adapter.persistence.entity.TrainingSampleEntity;
import com.drones.vision.adapter.persistence.mapper.TrainingSampleMapper;
import com.drones.vision.domain.model.DatasetId;
import com.drones.vision.domain.model.SampleStatus;
import com.drones.vision.domain.model.TrainingSample;
import com.drones.vision.domain.model.TrainingSampleId;
import com.drones.vision.domain.port.out.TrainingSampleRepositoryPort;

import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.TypedQuery;

import java.util.List;
import java.util.Optional;

/**
 * {@link TrainingSampleRepositoryPort} backed by Postgres via plain JPA (see {@link
 * JpaOperations}) — docs/plans/done/CV-TRAINING-PLAN.md §1, Wave T3.
 *
 * <p>{@link #save} is an upsert (merge-by-id), matching {@code InMemoryTrainingSampleRepository}'s
 * ({@code vision-app} devsupport) {@code Map#put} semantics exactly — a sample mutates over its
 * own review lifecycle (capture seeds PENDING, labeling replaces annotations and moves to
 * LABELED/DISCARDED), unlike {@code JpaDetectionRepository}'s append-only historical rows.
 *
 * <p>{@link #findByDataset}/{@link #countByDataset} both filter on {@code datasetId} plus an
 * optional {@code status} equality (folded into one JPQL string with a conditional clause rather
 * than two near-duplicate query strings, since the {@code WHERE} shape is otherwise identical) —
 * the exact {@code (dataset_id, status)} pair {@code idx_training_samples_dataset_status} indexes.
 * {@link #findByDataset} orders newest-captured-first before bounding to {@code limit} — a
 * concrete, deterministic order for what the port's javadoc leaves implementation-defined.
 */
public final class JpaTrainingSampleRepository implements TrainingSampleRepositoryPort {

    private final JpaOperations jpa;

    public JpaTrainingSampleRepository(EntityManagerFactory entityManagerFactory) {
        this.jpa = new JpaOperations(entityManagerFactory);
    }

    @Override
    public TrainingSample save(TrainingSample sample) {
        TrainingSampleEntity saved = jpa.write(em -> em.merge(TrainingSampleMapper.toEntity(sample)));
        return TrainingSampleMapper.toDomain(saved);
    }

    @Override
    public Optional<TrainingSample> findById(TrainingSampleId id) {
        return jpa.read(em -> Optional.ofNullable(em.find(TrainingSampleEntity.class, id.value())))
                .map(TrainingSampleMapper::toDomain);
    }

    @Override
    public List<TrainingSample> findByDataset(DatasetId datasetId, SampleStatus statusOrNull, int limit) {
        return jpa.read(em -> {
                    TypedQuery<TrainingSampleEntity> query = em.createQuery(
                            "select s from TrainingSampleEntity s where s.datasetId = :datasetId"
                                    + (statusOrNull == null ? "" : " and s.status = :status")
                                    + " order by s.capturedAt desc",
                            TrainingSampleEntity.class);
                    query.setParameter("datasetId", datasetId.value());
                    if (statusOrNull != null) {
                        query.setParameter("status", statusOrNull);
                    }
                    return query.setMaxResults(limit).getResultList();
                })
                .stream()
                .map(TrainingSampleMapper::toDomain)
                .toList();
    }

    @Override
    public int countByDataset(DatasetId datasetId, SampleStatus statusOrNull) {
        Long count = jpa.read(em -> {
            TypedQuery<Long> query = em.createQuery(
                    "select count(s) from TrainingSampleEntity s where s.datasetId = :datasetId"
                            + (statusOrNull == null ? "" : " and s.status = :status"),
                    Long.class);
            query.setParameter("datasetId", datasetId.value());
            if (statusOrNull != null) {
                query.setParameter("status", statusOrNull);
            }
            return query.getSingleResult();
        });
        return count.intValue();
    }

    @Override
    public void delete(TrainingSampleId id) {
        jpa.write(em -> {
            TrainingSampleEntity existing = em.find(TrainingSampleEntity.class, id.value());
            if (existing != null) {
                em.remove(existing);
            }
            return null;
        });
    }
}
