package com.drones.vision.adapter.persistence.repository;

import com.drones.vision.adapter.persistence.config.JpaOperations;
import com.drones.vision.adapter.persistence.entity.AssignmentEntity;
import com.drones.vision.adapter.persistence.entity.AssignmentId;
import com.drones.vision.identity.domain.model.Assignment;
import com.drones.vision.identity.domain.model.AssignmentRole;
import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.UserId;
import com.drones.vision.identity.domain.port.AssignmentRepositoryPort;

import jakarta.persistence.EntityManagerFactory;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * {@link AssignmentRepositoryPort} backed by Postgres via plain JPA (see {@link JpaOperations}) —
 * docs/plans/done/U-SCOPE-PLAN.md, U-e slice 2, feature 2.
 *
 * <p>{@link #assign} is an idempotent upsert via {@code merge} on the composite ({@code pilot},
 * {@code asset}) key — assigning an already-assigned pair re-persists the same row, never a
 * duplicate, matching {@code InMemoryAssignmentRepository}'s ({@code vision-app} devsupport)
 * set-semantics exactly. {@link #unassign} is a delete-if-present, idempotent (a missing link is a
 * no-op). The two directional reads are indexed queries; {@link #isAssigned} is a primary-key
 * lookup.
 *
 * <p>No {@code mapper} class for this repository (docs/plans/active/LAYERING-REFACTOR-PLAN.md §3/§7 row C):
 * unlike every other aggregate in this module, an assignment has no domain record of its own to
 * map to/from — {@link AssignmentEntity} is a bare join row, and every conversion here is a
 * one-line {@code UserId}/{@code AssetId} ↔ {@code UUID} wrap, already inlined at each call site
 * (e.g. {@code AssetId::new} in {@link #assetsForPilot}). Extracting a same-shaped
 * {@code AssignmentMapper} would be ceremony with no logic behind it.
 *
 * <p>{@link #roleFor}/{@link #assignmentsForAsset} (docs/plans/active/AUTH-ROLES-PLAN.md §3.4, wave
 * B3) read the {@code role} column added by {@code V33__assignment_roles.sql}; {@link Assignment}
 * (the one exception to the "no domain record to map to/from" note above — it exists precisely as
 * the read shape for a roster row with its seat) is built inline the same way.
 */
public final class JpaAssignmentRepository implements AssignmentRepositoryPort {

    private final JpaOperations jpa;

    public JpaAssignmentRepository(EntityManagerFactory entityManagerFactory) {
        this.jpa = new JpaOperations(entityManagerFactory);
    }

    @Override
    public void assign(UserId pilot, AssetId asset, AssignmentRole role) {
        jpa.write(em -> em.merge(new AssignmentEntity(pilot.value(), asset.value(), role.name())));
    }

    @Override
    public void unassign(UserId pilot, AssetId asset) {
        jpa.write(em -> {
            AssignmentEntity existing =
                    em.find(AssignmentEntity.class, new AssignmentId(pilot.value(), asset.value()));
            if (existing != null) {
                em.remove(existing);
            }
            return null;
        });
    }

    @Override
    public Set<AssetId> assetsForPilot(UserId pilot) {
        return jpa.read(em -> em.createQuery(
                        "select a.assetId from AssignmentEntity a where a.pilotUserId = :pilot", UUID.class)
                        .setParameter("pilot", pilot.value())
                        .getResultList())
                .stream()
                .map(AssetId::new)
                .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public Set<UserId> pilotsForAsset(AssetId asset) {
        return jpa.read(em -> em.createQuery(
                        "select a.pilotUserId from AssignmentEntity a where a.assetId = :asset", UUID.class)
                        .setParameter("asset", asset.value())
                        .getResultList())
                .stream()
                .map(UserId::new)
                .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public boolean isAssigned(UserId pilot, AssetId asset) {
        return jpa.read(em ->
                em.find(AssignmentEntity.class, new AssignmentId(pilot.value(), asset.value())) != null);
    }

    @Override
    public Optional<AssignmentRole> roleFor(UserId pilot, AssetId asset) {
        return jpa.read(em ->
                        Optional.ofNullable(em.find(AssignmentEntity.class, new AssignmentId(pilot.value(), asset.value()))))
                .map(entity -> AssignmentRole.valueOf(entity.role()));
    }

    @Override
    public List<Assignment> assignmentsForAsset(AssetId asset) {
        return jpa.read(em -> em.createQuery(
                        "select a from AssignmentEntity a where a.assetId = :asset", AssignmentEntity.class)
                        .setParameter("asset", asset.value())
                        .getResultList())
                .stream()
                .map(entity -> new Assignment(new UserId(entity.pilotUserId()), new AssetId(entity.assetId()),
                        AssignmentRole.valueOf(entity.role())))
                .toList();
    }
}
