package com.drones.vision.adapter.persistence.repository;

import com.drones.vision.adapter.persistence.config.JpaOperations;
import com.drones.vision.adapter.persistence.entity.CategoryEntity;
import com.drones.vision.adapter.persistence.mapper.CategoryMapper;
import com.drones.vision.domain.model.CategoryId;
import com.drones.vision.domain.model.DeviceCategory;
import com.drones.vision.domain.port.out.CategoryRepositoryPort;

import jakarta.persistence.EntityManagerFactory;

import java.util.List;
import java.util.Optional;

/**
 * {@link CategoryRepositoryPort} backed by Postgres via plain JPA (see {@link JpaOperations}).
 *
 * <p>{@link #save} is an upsert (merge-by-id), matching {@code InMemoryCategoryRepository}'s
 * {@code Map#put} semantics exactly — including that default seed categories are not created
 * here (unlike the in-memory fallback, which seeds them in its constructor): this adapter's
 * equivalent seed happens once, at schema-migration time, via {@code V2__seed_categories.sql}.
 */
public final class JpaCategoryRepository implements CategoryRepositoryPort {

    private final JpaOperations jpa;

    public JpaCategoryRepository(EntityManagerFactory entityManagerFactory) {
        this.jpa = new JpaOperations(entityManagerFactory);
    }

    @Override
    public DeviceCategory save(DeviceCategory category) {
        CategoryEntity saved = jpa.write(em -> em.merge(CategoryMapper.toEntity(category)));
        return CategoryMapper.toDomain(saved);
    }

    @Override
    public Optional<DeviceCategory> findById(CategoryId id) {
        return jpa.read(em -> Optional.ofNullable(em.find(CategoryEntity.class, id.slug())))
                .map(CategoryMapper::toDomain);
    }

    @Override
    public List<DeviceCategory> findAll() {
        return jpa.read(em -> em.createQuery("select c from CategoryEntity c", CategoryEntity.class)
                        .getResultList())
                .stream()
                .map(CategoryMapper::toDomain)
                .toList();
    }
}
