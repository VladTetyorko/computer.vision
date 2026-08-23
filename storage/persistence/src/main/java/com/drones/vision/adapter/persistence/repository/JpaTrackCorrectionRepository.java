package com.drones.vision.adapter.persistence.repository;

import com.drones.vision.adapter.persistence.config.JpaOperations;
import com.drones.vision.adapter.persistence.entity.TrackCorrectionEntity;
import com.drones.vision.adapter.persistence.mapper.TrackCorrectionMapper;
import com.drones.vision.flight.domain.model.TrackCorrection;
import com.drones.vision.flight.domain.port.TrackCorrectionRepositoryPort;
import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.UsageId;

import jakarta.persistence.EntityManagerFactory;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * {@link TrackCorrectionRepositoryPort} backed by Postgres via plain JPA (see {@link JpaOperations})
 * — docs/plans/done/VISUAL-GEO-V2-PLAN.md §3.5/§3.7, H5.
 *
 * <p>{@link #save} always {@code persist}s a brand-new row (append-only — {@link TrackCorrection}
 * carries no id to merge by, see {@link TrackCorrectionEntity}'s own javadoc), the same
 * "always persist" shape {@link JpaTrackTrailRepository} uses for its own immutable, append-only
 * rows. {@link #trimUsageToMostRecent} and {@link #deleteOlderThan} are each one bulk delete
 * rather than a fetch-then-delete round trip through the JVM, the same
 * delete-not-in-a-bounded-select shape {@link JpaTrackTrailRepository#trimToMostRecent} already
 * uses, scoped here to {@code usage_id} instead of {@code (asset_id, track_id)}.
 */
public final class JpaTrackCorrectionRepository implements TrackCorrectionRepositoryPort {

    private final JpaOperations jpa;

    public JpaTrackCorrectionRepository(EntityManagerFactory entityManagerFactory) {
        this.jpa = new JpaOperations(entityManagerFactory);
    }

    @Override
    public void save(TrackCorrection correction) {
        jpa.write(em -> {
            em.persist(TrackCorrectionMapper.toEntity(correction));
            return null;
        });
    }

    @Override
    public List<TrackCorrection> findByUsage(UsageId usageId, int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive: " + limit);
        }
        return jpa.read(em -> em.createQuery(
                        "select t from TrackCorrectionEntity t where t.usageId = :usageId "
                                + "order by t.frameAt asc, t.id asc",
                        TrackCorrectionEntity.class)
                        .setParameter("usageId", usageId.value())
                        .setMaxResults(limit)
                        .getResultList())
                .stream()
                .map(TrackCorrectionMapper::toDomain)
                .toList();
    }

    @Override
    public Optional<TrackCorrection> findLatest(AssetId assetId) {
        return jpa.read(em -> em.createQuery(
                        "select t from TrackCorrectionEntity t where t.assetId = :assetId "
                                + "order by t.frameAt desc, t.id desc",
                        TrackCorrectionEntity.class)
                        .setParameter("assetId", assetId.value())
                        .setMaxResults(1)
                        .getResultList())
                .stream()
                .findFirst()
                .map(TrackCorrectionMapper::toDomain);
    }

    @Override
    public int deleteOlderThan(Instant before) {
        return jpa.write(em -> em.createQuery("delete from TrackCorrectionEntity t where t.frameAt < :before")
                .setParameter("before", before)
                .executeUpdate());
    }

    @Override
    public int trimUsageToMostRecent(UsageId usageId, int maxRows) {
        if (maxRows <= 0) {
            throw new IllegalArgumentException("maxRows must be positive: " + maxRows);
        }
        return jpa.write(em -> em.createNativeQuery("""
                DELETE FROM track_corrections
                WHERE usage_id = ?1
                  AND id NOT IN (
                    SELECT id FROM track_corrections
                    WHERE usage_id = ?1
                    ORDER BY frame_at DESC, id DESC LIMIT ?2
                  )
                """)
                .setParameter(1, usageId.value())
                .setParameter(2, maxRows)
                .executeUpdate());
    }
}
