package com.drones.vision.adapter.persistence.repository;

import com.drones.vision.adapter.persistence.config.JpaOperations;
import com.drones.vision.adapter.persistence.entity.DiscoveryCandidateEntity;
import com.drones.vision.adapter.persistence.mapper.DiscoveryCandidateMapper;
import com.drones.vision.warehouse.domain.model.DiscoveryCandidate;
import com.drones.vision.warehouse.domain.model.DiscoveryCandidateId;
import com.drones.vision.warehouse.domain.port.DiscoveryCandidateRepositoryPort;

import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.TypedQuery;

import java.util.List;
import java.util.Optional;

/**
 * {@link DiscoveryCandidateRepositoryPort} backed by Postgres via plain JPA (see {@link
 * JpaOperations}) — docs/plans/active/ZERO-CONFIG-ONBOARDING-CONTEXT.md §11, Z2c.
 *
 * <p>{@link #save} is a {@code merge}, not a {@code persist}: a candidate mutates over its own
 * lifecycle (a re-report refreshes {@code discovered}/{@code lastSeen}; dismiss/register change
 * {@code status}), the same "mutates in place under its own id" shape {@link
 * JpaMaintenanceRepository#save}/{@link JpaControlProfileRepository#save} already follow.
 *
 * <p>This class makes no atomicity promise beyond a single {@code merge}/{@code find} — see {@code
 * DiscoveryCandidateRepositoryPort}'s own javadoc and {@code DefaultDiscoveryInboxService}'s class
 * javadoc for why the application layer, not this adapter, serializes the read-then-write upsert
 * {@link #findByIdentityKey} + {@link #save} together. The database-level {@code
 * uq_discovery_candidates_identity_key} unique index ({@code V31__discovery_inbox.sql}) is the
 * safety net across a restart or a second app instance, not a substitute for that in-process lock.
 */
public final class JpaDiscoveryCandidateRepository implements DiscoveryCandidateRepositoryPort {

    private final JpaOperations jpa;

    public JpaDiscoveryCandidateRepository(EntityManagerFactory entityManagerFactory) {
        this.jpa = new JpaOperations(entityManagerFactory);
    }

    @Override
    public DiscoveryCandidate save(DiscoveryCandidate candidate) {
        jpa.write(em -> {
            em.merge(DiscoveryCandidateMapper.toEntity(candidate));
            return null;
        });
        return candidate;
    }

    @Override
    public Optional<DiscoveryCandidate> findById(DiscoveryCandidateId id) {
        return jpa.read(em -> Optional.ofNullable(em.find(DiscoveryCandidateEntity.class, id.value())))
                .map(DiscoveryCandidateMapper::toDomain);
    }

    @Override
    public Optional<DiscoveryCandidate> findByIdentityKey(String identityKey) {
        return jpa.read(em -> {
            TypedQuery<DiscoveryCandidateEntity> query = em.createQuery(
                    "select c from DiscoveryCandidateEntity c where c.identityKey = :identityKey",
                    DiscoveryCandidateEntity.class);
            query.setParameter("identityKey", identityKey);
            return query.getResultList().stream().findFirst();
        }).map(DiscoveryCandidateMapper::toDomain);
    }

    @Override
    public List<DiscoveryCandidate> findAll() {
        return jpa.read(em -> em.createQuery(
                        "select c from DiscoveryCandidateEntity c order by c.lastSeen desc",
                        DiscoveryCandidateEntity.class)
                        .getResultList())
                .stream().map(DiscoveryCandidateMapper::toDomain).toList();
    }
}
