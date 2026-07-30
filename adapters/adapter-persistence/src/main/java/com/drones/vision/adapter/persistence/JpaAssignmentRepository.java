package com.drones.vision.adapter.persistence;

import com.drones.vision.adapter.persistence.entity.AssignmentEntity;
import com.drones.vision.adapter.persistence.entity.AssignmentId;
import com.drones.vision.domain.model.AssetId;
import com.drones.vision.domain.model.UserId;
import com.drones.vision.domain.port.out.AssignmentRepositoryPort;

import jakarta.persistence.EntityManagerFactory;

import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * {@link AssignmentRepositoryPort} backed by Postgres via plain JPA (see {@link JpaOperations}) —
 * docs/U-SCOPE-PLAN.md, U-e slice 2, feature 2.
 *
 * <p>{@link #assign} is an idempotent upsert via {@code merge} on the composite ({@code pilot},
 * {@code asset}) key — assigning an already-assigned pair re-persists the same row, never a
 * duplicate, matching {@code InMemoryAssignmentRepository}'s ({@code vision-app} devsupport)
 * set-semantics exactly. {@link #unassign} is a delete-if-present, idempotent (a missing link is a
 * no-op). The two directional reads are indexed queries; {@link #isAssigned} is a primary-key
 * lookup.
 */
public final class JpaAssignmentRepository implements AssignmentRepositoryPort {

    private final JpaOperations jpa;

    public JpaAssignmentRepository(EntityManagerFactory entityManagerFactory) {
        this.jpa = new JpaOperations(entityManagerFactory);
    }

    @Override
    public void assign(UserId pilot, AssetId asset) {
        jpa.write(em -> em.merge(new AssignmentEntity(pilot.value(), asset.value())));
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
}
