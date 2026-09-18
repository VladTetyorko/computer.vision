package com.drones.vision.adapter.persistence.repository;

import com.drones.vision.adapter.persistence.config.JpaOperations;
import com.drones.vision.adapter.persistence.entity.PairingEntity;
import com.drones.vision.adapter.persistence.mapper.PairingMapper;
import com.drones.vision.kernel.DeviceId;
import com.drones.vision.warehouse.domain.model.Pairing;
import com.drones.vision.warehouse.domain.model.PairingId;
import com.drones.vision.warehouse.domain.port.PairingRepositoryPort;

import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.TypedQuery;

import java.util.List;
import java.util.Optional;

/**
 * {@link PairingRepositoryPort} backed by Postgres via plain JPA (see {@link JpaOperations}),
 * docs/plans/active/LINK-PAIRING-PLAN.md §3.3.
 *
 * <p>{@link #save} is an upsert (merge-by-id), same convention {@code JpaDeviceRepository} follows.
 * {@link #deleteById} is a real hard delete (⚠ accepted deviation from soft-delete, §3.3 — a
 * forgotten pairing's sysid and key must stop being valid immediately) — idempotent, a missing id
 * is a no-op.
 */
public final class JpaPairingRepository implements PairingRepositoryPort {

    private final JpaOperations jpa;

    public JpaPairingRepository(EntityManagerFactory entityManagerFactory) {
        this.jpa = new JpaOperations(entityManagerFactory);
    }

    @Override
    public Pairing save(Pairing pairing) {
        PairingEntity saved = jpa.write(em -> em.merge(PairingMapper.toEntity(pairing)));
        return PairingMapper.toDomain(saved);
    }

    @Override
    public Optional<Pairing> findById(PairingId id) {
        return jpa.read(em -> Optional.ofNullable(em.find(PairingEntity.class, id.value())))
                .map(PairingMapper::toDomain);
    }

    @Override
    public Optional<Pairing> findByDeviceId(DeviceId deviceId) {
        return jpa.read(em -> {
            TypedQuery<PairingEntity> query = em.createQuery(
                    "select p from PairingEntity p where p.deviceId = :deviceId", PairingEntity.class);
            query.setParameter("deviceId", deviceId.value());
            return query.getResultList().stream().findFirst();
        }).map(PairingMapper::toDomain);
    }

    @Override
    public Optional<Pairing> findBySysid(int sysid) {
        return jpa.read(em -> {
            TypedQuery<PairingEntity> query = em.createQuery(
                    "select p from PairingEntity p where p.sysid = :sysid", PairingEntity.class);
            query.setParameter("sysid", sysid);
            return query.getResultList().stream().findFirst();
        }).map(PairingMapper::toDomain);
    }

    @Override
    public List<Pairing> findAll() {
        return jpa.read(em -> em.createQuery("select p from PairingEntity p", PairingEntity.class)
                        .getResultList())
                .stream()
                .map(PairingMapper::toDomain)
                .toList();
    }

    @Override
    public void deleteById(PairingId id) {
        jpa.write(em -> {
            PairingEntity existing = em.find(PairingEntity.class, id.value());
            if (existing != null) {
                em.remove(existing);
            }
            return null;
        });
    }
}
