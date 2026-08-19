package com.drones.vision.adapter.persistence.repository;

import com.drones.vision.adapter.persistence.config.JpaOperations;
import com.drones.vision.adapter.persistence.entity.TrackPointEntity;
import com.drones.vision.adapter.persistence.mapper.TrackPointMapper;
import com.drones.vision.kernel.AssetId;
import com.drones.vision.map.domain.model.TrackPoint;
import com.drones.vision.map.domain.port.TrackTrailRepositoryPort;

import jakarta.persistence.EntityManagerFactory;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * {@link TrackTrailRepositoryPort} backed by Postgres via plain JPA (see {@link JpaOperations}) —
 * docs/plans/active/FIXED-CAMERA-GEO-PLAN.md decision D3/§7.
 *
 * <p>{@link #save} always {@code persist}s a brand-new row (append-only — {@link TrackPoint}
 * carries no id to merge by, see {@link TrackPointEntity}'s own javadoc), the same "always
 * persist" shape {@link JpaDetectionRepository}/{@link JpaTelemetryRepository} use for their own
 * immutable, append-only rows. {@link #trimToMostRecent} and {@link #deleteOlderThan} are each one
 * bulk delete rather than a fetch-then-delete round trip through the JVM, matching the port's own
 * javadoc — {@link #trimToMostRecent}'s "keep only the newest N" query is the same
 * delete-not-in-a-bounded-select shape {@link JpaDetectionEventRepository} already uses for its
 * own per-group retention cap, scoped here to {@code (asset_id, track_id)} instead of a bare
 * {@code stream_id}.
 */
public final class JpaTrackTrailRepository implements TrackTrailRepositoryPort {

    private final JpaOperations jpa;

    public JpaTrackTrailRepository(EntityManagerFactory entityManagerFactory) {
        this.jpa = new JpaOperations(entityManagerFactory);
    }

    @Override
    public TrackPoint save(TrackPoint point) {
        jpa.write(em -> {
            em.persist(TrackPointMapper.toEntity(point));
            return null;
        });
        return point;
    }

    @Override
    public List<TrackPoint> findByTrack(AssetId assetId, long trackId) {
        return jpa.read(em -> em.createQuery(
                        "select t from TrackPointEntity t where t.assetId = :assetId and t.trackId = :trackId "
                                + "order by t.capturedAt asc, t.id asc",
                        TrackPointEntity.class)
                        .setParameter("assetId", assetId.value())
                        .setParameter("trackId", trackId)
                        .getResultList())
                .stream()
                .map(TrackPointMapper::toDomain)
                .toList();
    }

    @Override
    public Optional<TrackPoint> findLatest(AssetId assetId, long trackId) {
        return jpa.read(em -> em.createQuery(
                        "select t from TrackPointEntity t where t.assetId = :assetId and t.trackId = :trackId "
                                + "order by t.capturedAt desc, t.id desc",
                        TrackPointEntity.class)
                        .setParameter("assetId", assetId.value())
                        .setParameter("trackId", trackId)
                        .setMaxResults(1)
                        .getResultList())
                .stream()
                .findFirst()
                .map(TrackPointMapper::toDomain);
    }

    @Override
    public void trimToMostRecent(AssetId assetId, long trackId, int maxPoints) {
        if (maxPoints <= 0) {
            throw new IllegalArgumentException("maxPoints must be positive: " + maxPoints);
        }
        jpa.write(em -> {
            em.createNativeQuery("""
                    DELETE FROM projected_track_points
                    WHERE asset_id = ?1 AND track_id = ?2
                      AND id NOT IN (
                        SELECT id FROM projected_track_points
                        WHERE asset_id = ?1 AND track_id = ?2
                        ORDER BY captured_at DESC, id DESC LIMIT ?3
                      )
                    """)
                    .setParameter(1, assetId.value())
                    .setParameter(2, trackId)
                    .setParameter(3, maxPoints)
                    .executeUpdate();
            return null;
        });
    }

    @Override
    public void deleteOlderThan(Instant cutoff) {
        jpa.write(em -> {
            em.createQuery("delete from TrackPointEntity t where t.capturedAt < :cutoff")
                    .setParameter("cutoff", cutoff)
                    .executeUpdate();
            return null;
        });
    }
}
