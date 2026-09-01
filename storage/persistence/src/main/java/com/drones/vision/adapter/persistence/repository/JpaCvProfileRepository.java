package com.drones.vision.adapter.persistence.repository;

import com.drones.vision.adapter.persistence.config.JpaOperations;
import com.drones.vision.adapter.persistence.entity.CvProfileBindingEntity;
import com.drones.vision.adapter.persistence.entity.CvProfileBindingId;
import com.drones.vision.adapter.persistence.entity.CvProfileEntity;
import com.drones.vision.adapter.persistence.mapper.CvProfileMapper;
import com.drones.vision.kernel.GroupId;
import com.drones.vision.perception.domain.model.BindingScope;
import com.drones.vision.perception.domain.model.CvProfile;
import com.drones.vision.perception.domain.model.CvProfileBinding;
import com.drones.vision.perception.domain.model.CvProfileId;
import com.drones.vision.perception.domain.port.CvProfileRepositoryPort;

import jakarta.persistence.EntityManagerFactory;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link CvProfileRepositoryPort} backed by Postgres via plain JPA (see {@link JpaOperations}) —
 * docs/plans/active/CV-SETTINGS-PLAN.md §5.3, CV-SETTINGS wave W3.
 *
 * <p>{@link #save} and {@link #saveBinding} are {@code merge}s — a profile is edited in place under
 * a stable id (the same "referred to by id across edits" shape {@code JpaControlProfileRepository}
 * follows), and a binding is upserted by its composite {@code (scopeKind, scopeId)} key (same shape
 * as {@code JpaAssignmentRepository}). {@link #findAllByGroup} needs no extra filtering to exclude
 * built-ins — a built-in's {@code group_id} is always {@code NULL} ({@code CvProfile}'s own
 * bidirectional invariant), and no {@code UUID} parameter ever matches a {@code NULL} column in
 * JPQL, so the query excludes them for free. {@link #delete}/{@link #deleteBinding} are real hard
 * deletes, idempotent — same convention as every other port here.
 */
public final class JpaCvProfileRepository implements CvProfileRepositoryPort {

    private final JpaOperations jpa;

    public JpaCvProfileRepository(EntityManagerFactory entityManagerFactory) {
        this.jpa = new JpaOperations(entityManagerFactory);
    }

    @Override
    public Optional<CvProfile> findById(CvProfileId id) {
        return jpa.read(em -> Optional.ofNullable(em.find(CvProfileEntity.class, id.value())))
                .map(CvProfileMapper::toDomain);
    }

    @Override
    public List<CvProfile> findAll() {
        return jpa.read(em -> em.createQuery("select p from CvProfileEntity p", CvProfileEntity.class)
                        .getResultList())
                .stream()
                .map(CvProfileMapper::toDomain)
                .toList();
    }

    @Override
    public List<CvProfile> findAllByGroup(GroupId groupId) {
        return jpa.read(em -> em.createQuery(
                        "select p from CvProfileEntity p where p.groupId = :groupId", CvProfileEntity.class)
                        .setParameter("groupId", groupId.value())
                        .getResultList())
                .stream()
                .map(CvProfileMapper::toDomain)
                .toList();
    }

    @Override
    public CvProfile save(CvProfile profile) {
        CvProfileEntity saved = jpa.write(em -> em.merge(CvProfileMapper.toEntity(profile)));
        return CvProfileMapper.toDomain(saved);
    }

    @Override
    public void delete(CvProfileId id) {
        jpa.write(em -> {
            CvProfileEntity existing = em.find(CvProfileEntity.class, id.value());
            if (existing != null) {
                em.remove(existing);
            }
            return null;
        });
    }

    @Override
    public Optional<CvProfileBinding> findBinding(BindingScope scopeKind, String scopeId) {
        return jpa.read(em -> Optional.ofNullable(
                        em.find(CvProfileBindingEntity.class, new CvProfileBindingId(scopeKind.name(), scopeId))))
                .map(CvProfileMapper::toDomain);
    }

    @Override
    public List<CvProfileBinding> findAllBindings() {
        return jpa.read(em -> em.createQuery("select b from CvProfileBindingEntity b", CvProfileBindingEntity.class)
                        .getResultList())
                .stream()
                .map(CvProfileMapper::toDomain)
                .toList();
    }

    @Override
    public CvProfileBinding saveBinding(CvProfileBinding binding) {
        CvProfileBindingEntity saved = jpa.write(em -> em.merge(CvProfileMapper.toEntity(binding)));
        return CvProfileMapper.toDomain(saved);
    }

    @Override
    public void deleteBinding(BindingScope scopeKind, String scopeId) {
        jpa.write(em -> {
            CvProfileBindingEntity existing =
                    em.find(CvProfileBindingEntity.class, new CvProfileBindingId(scopeKind.name(), scopeId));
            if (existing != null) {
                em.remove(existing);
            }
            return null;
        });
    }

    @Override
    public int countBindingsFor(CvProfileId profileId) {
        UUID value = profileId.value();
        return jpa.read(em -> em.createQuery(
                        "select count(b) from CvProfileBindingEntity b where b.profileId = :profileId", Long.class)
                        .setParameter("profileId", value)
                        .getSingleResult())
                .intValue();
    }
}
