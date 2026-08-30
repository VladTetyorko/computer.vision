package com.drones.vision.adapter.persistence.repository;

import com.drones.vision.adapter.persistence.config.JpaOperations;
import com.drones.vision.adapter.persistence.entity.MaintenanceRecordEntity;
import com.drones.vision.adapter.persistence.mapper.MaintenanceRecordMapper;
import com.drones.vision.kernel.AssetId;
import com.drones.vision.warehouse.domain.model.MaintenanceId;
import com.drones.vision.warehouse.domain.model.MaintenanceRecord;
import com.drones.vision.warehouse.domain.port.MaintenanceRepositoryPort;

import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.TypedQuery;

import java.util.List;
import java.util.Optional;

/**
 * {@link MaintenanceRepositoryPort} backed by Postgres via plain JPA (see {@link JpaOperations}) —
 * docs/plans/active/WAREHOUSE-UX-PLAN.md D7.
 *
 * <p>{@link #save} is a {@code merge}, not a {@code persist}: unlike this module's append-only
 * repositories, a record is opened and later {@code close}d under the same id (same "mutates over
 * its own lifecycle" shape {@link JpaControlProfileRepository#save} follows), so a second save for
 * the same {@link MaintenanceId} must update the existing row, not fail on a duplicate key.
 *
 * <p>{@link #findOpen()}/{@link #findRecentlyClosed(int)} (docs/plans/active/WAREHOUSE-UX-PLAN.md
 * &sect;3.3, D5) are {@link #findOpenByAsset(AssetId)}/{@link #findByAsset(AssetId)}'s fleet-wide
 * counterparts, over the same {@code closed_at}/{@code opened_at} columns without an {@code
 * asset_id} predicate — {@code GET /api/maintenance}'s backing queries.
 */
public final class JpaMaintenanceRepository implements MaintenanceRepositoryPort {

    private final JpaOperations jpa;

    public JpaMaintenanceRepository(EntityManagerFactory entityManagerFactory) {
        this.jpa = new JpaOperations(entityManagerFactory);
    }

    @Override
    public MaintenanceRecord save(MaintenanceRecord record) {
        jpa.write(em -> {
            em.merge(MaintenanceRecordMapper.toEntity(record));
            return null;
        });
        return record;
    }

    @Override
    public Optional<MaintenanceRecord> findById(MaintenanceId id) {
        return jpa.read(em -> Optional.ofNullable(em.find(MaintenanceRecordEntity.class, id.value())))
                .map(MaintenanceRecordMapper::toDomain);
    }

    @Override
    public List<MaintenanceRecord> findByAsset(AssetId assetId) {
        return jpa.read(em -> {
            TypedQuery<MaintenanceRecordEntity> query = em.createQuery(
                    "select m from MaintenanceRecordEntity m where m.assetId = :assetId "
                            + "order by m.openedAt desc",
                    MaintenanceRecordEntity.class);
            query.setParameter("assetId", assetId.value());
            return query.getResultList();
        }).stream().map(MaintenanceRecordMapper::toDomain).toList();
    }

    @Override
    public List<MaintenanceRecord> findOpenByAsset(AssetId assetId) {
        return jpa.read(em -> {
            TypedQuery<MaintenanceRecordEntity> query = em.createQuery(
                    "select m from MaintenanceRecordEntity m where m.assetId = :assetId "
                            + "and m.closedAt is null order by m.openedAt desc",
                    MaintenanceRecordEntity.class);
            query.setParameter("assetId", assetId.value());
            return query.getResultList();
        }).stream().map(MaintenanceRecordMapper::toDomain).toList();
    }

    @Override
    public List<MaintenanceRecord> findOpen() {
        return jpa.read(em -> em.createQuery(
                        "select m from MaintenanceRecordEntity m where m.closedAt is null order by m.openedAt desc",
                        MaintenanceRecordEntity.class)
                        .getResultList())
                .stream().map(MaintenanceRecordMapper::toDomain).toList();
    }

    @Override
    public List<MaintenanceRecord> findRecentlyClosed(int limit) {
        return jpa.read(em -> em.createQuery(
                        "select m from MaintenanceRecordEntity m where m.closedAt is not null "
                                + "order by m.closedAt desc",
                        MaintenanceRecordEntity.class)
                        .setMaxResults(limit)
                        .getResultList())
                .stream().map(MaintenanceRecordMapper::toDomain).toList();
    }
}
