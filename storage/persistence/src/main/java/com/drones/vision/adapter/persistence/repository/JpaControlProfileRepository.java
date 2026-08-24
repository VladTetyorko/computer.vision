package com.drones.vision.adapter.persistence.repository;

import com.drones.vision.adapter.persistence.config.JpaOperations;
import com.drones.vision.adapter.persistence.entity.ControlProfileEntity;
import com.drones.vision.adapter.persistence.mapper.ControlProfileMapper;
import com.drones.vision.flight.domain.model.ControlProfileId;
import com.drones.vision.flight.domain.model.OwnedControlProfile;
import com.drones.vision.flight.domain.model.VehicleKind;
import com.drones.vision.flight.domain.port.ControlProfileRepositoryPort;
import com.drones.vision.kernel.UserId;

import jakarta.persistence.EntityManagerFactory;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

/**
 * {@link ControlProfileRepositoryPort} backed by Postgres via plain JPA (see {@link JpaOperations}) —
 * docs/plans/active/CONTROLLER-SETUP-CONTEXT.md wave C4.
 *
 * <p>{@link #save} is a {@code merge}, not a {@code persist}: unlike this module's append-only
 * repositories a profile is edited in place under a stable id, which is what lets a browser keep
 * referring to "the profile I am editing" across saves.
 *
 * <p>{@link #activate} clears and sets the flag <b>inside one transaction</b>, as the port requires.
 * {@code uq_control_profiles_active} (V24) is the belt to this braces: even if some future caller
 * wrote the flag another way, the database refuses a second active profile for the same
 * {@code (owner, kind)} rather than letting {@link #findActive} start picking by read order.
 */
public final class JpaControlProfileRepository implements ControlProfileRepositoryPort {

    private final JpaOperations jpa;

    public JpaControlProfileRepository(EntityManagerFactory entityManagerFactory) {
        this.jpa = new JpaOperations(entityManagerFactory);
    }

    @Override
    public List<OwnedControlProfile> findAllByOwner(UserId owner) {
        return jpa.read(em -> em.createQuery(
                        "select p from ControlProfileEntity p where p.ownerUserId = :owner "
                                + "order by p.updatedAt desc", ControlProfileEntity.class)
                .setParameter("owner", owner.value())
                .getResultList()).stream().map(ControlProfileMapper::toDomain).toList();
    }

    @Override
    public Optional<OwnedControlProfile> findById(ControlProfileId id) {
        return jpa.read(em -> Optional.ofNullable(em.find(ControlProfileEntity.class, id.value())))
                .map(ControlProfileMapper::toDomain);
    }

    @Override
    public Optional<OwnedControlProfile> findActive(UserId owner, VehicleKind kind) {
        return jpa.read(em -> em.createQuery(
                        "select p from ControlProfileEntity p where p.ownerUserId = :owner "
                                + "and p.vehicleKind = :kind and p.active = true", ControlProfileEntity.class)
                .setParameter("owner", owner.value())
                .setParameter("kind", kind.name())
                .setMaxResults(1)
                .getResultList()).stream().findFirst().map(ControlProfileMapper::toDomain);
    }

    @Override
    public void save(OwnedControlProfile profile) {
        jpa.write(em -> {
            em.merge(ControlProfileMapper.toEntity(profile));
            return null;
        });
    }

    @Override
    public void activate(UserId owner, ControlProfileId id) {
        jpa.write(em -> {
            ControlProfileEntity target = em.find(ControlProfileEntity.class, id.value());
            if (target == null || !target.ownerUserId().equals(owner.value())) {
                throw new NoSuchElementException("No control profile " + id.value() + " for this operator");
            }
            // Clear first, then set: the partial unique index would reject the other order the
            // moment an operator moves the flag between two of their own profiles.
            em.createQuery("update ControlProfileEntity p set p.active = false "
                            + "where p.ownerUserId = :owner and p.vehicleKind = :kind and p.active = true")
                    .setParameter("owner", owner.value())
                    .setParameter("kind", target.vehicleKind())
                    .executeUpdate();
            em.createQuery("update ControlProfileEntity p set p.active = true where p.id = :id")
                    .setParameter("id", id.value())
                    .executeUpdate();
            return null;
        });
    }

    @Override
    public void delete(ControlProfileId id) {
        jpa.write(em -> {
            ControlProfileEntity existing = em.find(ControlProfileEntity.class, id.value());
            if (existing != null) {
                em.remove(existing);
            }
            return null;
        });
    }
}
