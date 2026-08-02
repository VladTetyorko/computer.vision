package com.drones.vision.adapter.persistence.repository;

import com.drones.vision.adapter.persistence.config.JpaOperations;
import com.drones.vision.adapter.persistence.entity.GroupEntity;
import com.drones.vision.adapter.persistence.mapper.GroupMapper;
import com.drones.vision.domain.model.Group;
import com.drones.vision.domain.model.GroupId;
import com.drones.vision.domain.port.out.GroupRepositoryPort;

import jakarta.persistence.EntityManagerFactory;

import java.util.List;
import java.util.Optional;

/**
 * {@link GroupRepositoryPort} backed by Postgres via plain JPA (see {@link JpaOperations}) —
 * docs/U-AUTH-PLAN.md, wave 3.
 *
 * <p>{@link #save} is an upsert (merge-by-id), matching {@code InMemoryGroupRepository}'s ({@code
 * vision-app} devsupport) {@code Map#put}. {@code parentGroupId} maps straight through as a
 * nullable {@code UUID} column.
 */
public final class JpaGroupRepository implements GroupRepositoryPort {

    private final JpaOperations jpa;

    public JpaGroupRepository(EntityManagerFactory entityManagerFactory) {
        this.jpa = new JpaOperations(entityManagerFactory);
    }

    @Override
    public Optional<Group> findById(GroupId id) {
        return jpa.read(em -> Optional.ofNullable(em.find(GroupEntity.class, id.value())))
                .map(GroupMapper::toDomain);
    }

    @Override
    public List<Group> findAll() {
        return jpa.read(em -> em.createQuery("select g from GroupEntity g", GroupEntity.class).getResultList())
                .stream()
                .map(GroupMapper::toDomain)
                .toList();
    }

    @Override
    public Group save(Group group) {
        GroupEntity saved = jpa.write(em -> em.merge(GroupMapper.toEntity(group)));
        return GroupMapper.toDomain(saved);
    }
}
