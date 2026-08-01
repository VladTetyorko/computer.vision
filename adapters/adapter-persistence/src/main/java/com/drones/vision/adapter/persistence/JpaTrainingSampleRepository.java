package com.drones.vision.adapter.persistence;

import com.drones.vision.adapter.persistence.entity.TrainingSampleEntity;
import com.drones.vision.domain.model.AssetId;
import com.drones.vision.domain.model.DatasetId;
import com.drones.vision.domain.model.SampleStatus;
import com.drones.vision.domain.model.StreamId;
import com.drones.vision.domain.model.TrainingSample;
import com.drones.vision.domain.model.TrainingSampleId;
import com.drones.vision.domain.model.UserId;
import com.drones.vision.domain.port.out.TrainingSampleRepositoryPort;

import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.TypedQuery;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link TrainingSampleRepositoryPort} backed by Postgres via plain JPA (see {@link
 * JpaOperations}) — docs/CV-TRAINING-PLAN.md §1, Wave T3.
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
        TrainingSampleEntity saved = jpa.write(em -> em.merge(toEntity(sample)));
        return toDomain(saved);
    }

    @Override
    public Optional<TrainingSample> findById(TrainingSampleId id) {
        return jpa.read(em -> Optional.ofNullable(em.find(TrainingSampleEntity.class, id.value())))
                .map(JpaTrainingSampleRepository::toDomain);
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
                .map(JpaTrainingSampleRepository::toDomain)
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

    private static TrainingSampleEntity toEntity(TrainingSample sample) {
        AssetId assetId = sample.assetId();
        UserId labeledBy = sample.labeledBy();
        return new TrainingSampleEntity(sample.id().value(), sample.datasetId().value(),
                sample.streamId().value(), assetId == null ? null : assetId.value(), sample.capturedAt(),
                sample.width(), sample.height(), sample.annotations(), sample.status(),
                labeledBy == null ? null : labeledBy.value(), sample.labeledAt());
    }

    private static TrainingSample toDomain(TrainingSampleEntity entity) {
        UUID assetId = entity.assetId();
        UUID labeledBy = entity.labeledBy();
        return new TrainingSample(new TrainingSampleId(entity.id()), new DatasetId(entity.datasetId()),
                new StreamId(entity.streamId()), assetId == null ? null : new AssetId(assetId),
                entity.capturedAt(), entity.width(), entity.height(), entity.annotations(), entity.status(),
                labeledBy == null ? null : new UserId(labeledBy), entity.labeledAt());
    }
}
