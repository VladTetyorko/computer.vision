package com.drones.vision.adapter.persistence.repository;

import com.drones.vision.adapter.persistence.config.JpaOperations;
import com.drones.vision.adapter.persistence.entity.SampleImageEntity;
import com.drones.vision.adapter.persistence.mapper.SampleImageMapper;
import com.drones.vision.learning.domain.model.SampleImage;
import com.drones.vision.learning.domain.model.TrainingSampleId;
import com.drones.vision.learning.domain.port.SampleImageStorePort;

import jakarta.persistence.EntityManagerFactory;

import java.util.Optional;

/**
 * {@link SampleImageStorePort} backed by Postgres via plain JPA (see {@link JpaOperations}) —
 * docs/plans/done/CV-TRAINING-PLAN.md §1/§C, Wave T3 — the {@code JpaAssetImageRepository} shape, verbatim,
 * reused for training-sample frames instead of asset photos.
 *
 * <p>{@link #save} is an upsert (merge-by-{@code sampleId}), matching {@code
 * InMemorySampleImageStore}'s ({@code vision-app} devsupport) {@code Map#put} exactly.
 */
public final class JpaSampleImageStore implements SampleImageStorePort {

    private final JpaOperations jpa;

    public JpaSampleImageStore(EntityManagerFactory entityManagerFactory) {
        this.jpa = new JpaOperations(entityManagerFactory);
    }

    @Override
    public void save(TrainingSampleId id, SampleImage image) {
        jpa.write(em -> em.merge(SampleImageMapper.toEntity(id, image)));
    }

    @Override
    public Optional<SampleImage> findById(TrainingSampleId id) {
        return jpa.read(em -> Optional.ofNullable(em.find(SampleImageEntity.class, id.value())))
                .map(SampleImageMapper::toDomain);
    }

    @Override
    public void delete(TrainingSampleId id) {
        jpa.write(em -> {
            SampleImageEntity existing = em.find(SampleImageEntity.class, id.value());
            if (existing != null) {
                em.remove(existing);
            }
            return null;
        });
    }
}
