package com.drones.vision.adapter.persistence.repository;

import com.drones.vision.adapter.persistence.config.JpaOperations;
import com.drones.vision.adapter.persistence.entity.DatasetEntity;
import com.drones.vision.adapter.persistence.mapper.DatasetMapper;
import com.drones.vision.learning.domain.model.Dataset;
import com.drones.vision.learning.domain.model.DatasetId;
import com.drones.vision.learning.domain.port.DatasetRepositoryPort;

import jakarta.persistence.EntityManagerFactory;

import java.util.List;
import java.util.Optional;

/**
 * {@link DatasetRepositoryPort} backed by Postgres via plain JPA (see {@link JpaOperations}) —
 * docs/plans/done/CV-TRAINING-PLAN.md §1, Wave T3.
 *
 * <p>{@link #save} is an upsert (merge-by-id), matching {@code InMemoryDatasetRepository}'s
 * ({@code vision-app} devsupport) {@code Map#put} semantics exactly. {@link #delete} is a real
 * hard delete, idempotent (a missing id is a no-op) and does not cascade to the dataset's samples
 * or images — same "no referential integrity between repositories" convention as every other
 * port here.
 */
public final class JpaDatasetRepository implements DatasetRepositoryPort {

    private final JpaOperations jpa;

    public JpaDatasetRepository(EntityManagerFactory entityManagerFactory) {
        this.jpa = new JpaOperations(entityManagerFactory);
    }

    @Override
    public Dataset save(Dataset dataset) {
        DatasetEntity saved = jpa.write(em -> em.merge(DatasetMapper.toEntity(dataset)));
        return DatasetMapper.toDomain(saved);
    }

    @Override
    public Optional<Dataset> findById(DatasetId id) {
        return jpa.read(em -> Optional.ofNullable(em.find(DatasetEntity.class, id.value())))
                .map(DatasetMapper::toDomain);
    }

    @Override
    public List<Dataset> findAll() {
        return jpa.read(em -> em.createQuery("select d from DatasetEntity d", DatasetEntity.class)
                        .getResultList())
                .stream()
                .map(DatasetMapper::toDomain)
                .toList();
    }

    @Override
    public void delete(DatasetId id) {
        jpa.write(em -> {
            DatasetEntity existing = em.find(DatasetEntity.class, id.value());
            if (existing != null) {
                em.remove(existing);
            }
            return null;
        });
    }
}
