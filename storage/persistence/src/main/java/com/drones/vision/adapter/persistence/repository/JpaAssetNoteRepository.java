package com.drones.vision.adapter.persistence.repository;

import com.drones.vision.adapter.persistence.config.JpaOperations;
import com.drones.vision.adapter.persistence.entity.AssetNoteEntity;
import com.drones.vision.adapter.persistence.mapper.AssetNoteMapper;
import com.drones.vision.kernel.AssetId;
import com.drones.vision.warehouse.domain.model.AssetNote;
import com.drones.vision.warehouse.domain.port.AssetNoteRepositoryPort;

import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.TypedQuery;

import java.util.List;

/**
 * {@link AssetNoteRepositoryPort} backed by Postgres via plain JPA (see {@link JpaOperations}) —
 * docs/plans/active/WAREHOUSE-UX-PLAN.md D7.
 *
 * <p>{@link #save} always {@code persist}s a brand-new row, never {@code merge}s — per the port's
 * own contract, notes are append-only and never edited or deleted (same shape {@link
 * JpaAuditTrail#record} uses for its own immutable history rows).
 */
public final class JpaAssetNoteRepository implements AssetNoteRepositoryPort {

    private final JpaOperations jpa;

    public JpaAssetNoteRepository(EntityManagerFactory entityManagerFactory) {
        this.jpa = new JpaOperations(entityManagerFactory);
    }

    @Override
    public AssetNote save(AssetNote note) {
        jpa.write(em -> {
            em.persist(AssetNoteMapper.toEntity(note));
            return null;
        });
        return note;
    }

    @Override
    public List<AssetNote> findByAsset(AssetId assetId) {
        return jpa.read(em -> {
            TypedQuery<AssetNoteEntity> query = em.createQuery(
                    "select n from AssetNoteEntity n where n.assetId = :assetId order by n.at desc",
                    AssetNoteEntity.class);
            query.setParameter("assetId", assetId.value());
            return query.getResultList();
        }).stream().map(AssetNoteMapper::toDomain).toList();
    }
}
