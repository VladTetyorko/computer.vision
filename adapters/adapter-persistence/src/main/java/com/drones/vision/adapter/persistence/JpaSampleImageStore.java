package com.drones.vision.adapter.persistence;

import com.drones.vision.adapter.persistence.entity.SampleImageEntity;
import com.drones.vision.domain.model.SampleImage;
import com.drones.vision.domain.model.TrainingSampleId;
import com.drones.vision.domain.port.out.SampleImageStorePort;

import jakarta.persistence.EntityManagerFactory;

import java.time.Instant;
import java.util.Optional;

/**
 * {@link SampleImageStorePort} backed by Postgres via plain JPA (see {@link JpaOperations}) —
 * docs/CV-TRAINING-PLAN.md §1/§C, Wave T3 — the {@code JpaAssetImageRepository} shape, verbatim,
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
        jpa.write(em -> em.merge(
                new SampleImageEntity(id.value(), image.contentType(), image.data(), Instant.now())));
    }

    @Override
    public Optional<SampleImage> findById(TrainingSampleId id) {
        return jpa.read(em -> Optional.ofNullable(em.find(SampleImageEntity.class, id.value())))
                .map(entity -> new SampleImage(entity.data(), entity.contentType()));
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
