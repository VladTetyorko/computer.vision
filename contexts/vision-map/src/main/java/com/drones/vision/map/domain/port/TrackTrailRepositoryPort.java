package com.drones.vision.map.domain.port;

import com.drones.vision.kernel.AssetId;
import com.drones.vision.map.domain.model.TrackPoint;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Driven port: the durable, decimated trail behind a live {@code ProjectedTrack} (docs/plans/active/
 * FIXED-CAMERA-GEO-PLAN.md decision D3, §7 table {@code projected_track_points}).
 *
 * <p>Deliberately thin: <em>whether</em> to append a point (D7's decimation — only after the track
 * has moved {@code trail.min-distance-meters}) is {@code TrackProjectionService}'s decision, made by
 * comparing a new fix against {@link #findLatest}; this port only stores, reads back, caps and
 * prunes. {@link #trimToMostRecent} and {@link #deleteOlderThan} are phrased as single port
 * operations rather than "fetch everything then delete in the caller" so an adapter can implement
 * both as one efficient bulk delete rather than round-tripping full trails through the JVM.
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li>{@link #save(TrackPoint)} always inserts — the trail is append-only, never upserted.</li>
 *   <li>{@link #findByTrack(AssetId, long)} returns every stored point for that track, oldest to
 *       newest — an unknown or empty track returns an empty list, not an error.</li>
 *   <li>{@link #findLatest(AssetId, long)} returns the most recently captured point, or {@link
 *       Optional#empty()} if the track has no stored points yet.</li>
 *   <li>{@link #trimToMostRecent(AssetId, long, int)} deletes every point for that track older than
 *       the {@code maxPoints} most recent — the D7 per-track cap. A track with fewer stored points
 *       than {@code maxPoints} is left untouched.</li>
 *   <li>{@link #deleteOlderThan(Instant)} deletes every point, across every track, captured strictly
 *       before {@code cutoff} — the D7 retention prune, run by the projection runner on its own
 *       cadence.</li>
 * </ul>
 *
 * <h2>Threading</h2>
 * Implementations must be safe for concurrent use — the projection runner appends on its own
 * cadence while reads (rebuilding a trail for {@code GET /api/map/tracks}) happen concurrently.
 */
public interface TrackTrailRepositoryPort {

    /**
     * Appends one trail point.
     *
     * @param point the point to store
     * @return the stored point
     */
    TrackPoint save(TrackPoint point);

    /**
     * Lists every stored point for one track, oldest to newest.
     *
     * @param assetId the camera asset
     * @param trackId the track id
     * @return an immutable snapshot, oldest first; empty if the track has no stored points
     */
    List<TrackPoint> findByTrack(AssetId assetId, long trackId);

    /**
     * Finds the most recently captured stored point for one track — what a caller compares a new fix
     * against to decide whether it has moved far enough to append (D7).
     *
     * @param assetId the camera asset
     * @param trackId the track id
     * @return the latest point, or {@link Optional#empty()} if none is stored
     */
    Optional<TrackPoint> findLatest(AssetId assetId, long trackId);

    /**
     * Trims one track's stored trail down to its {@code maxPoints} most recent points (D7's
     * per-track cap), deleting the rest. A no-op if the track already has {@code maxPoints} or fewer
     * stored points.
     *
     * @param assetId   the camera asset
     * @param trackId   the track id
     * @param maxPoints how many of the most recent points to keep; must be positive
     */
    void trimToMostRecent(AssetId assetId, long trackId, int maxPoints);

    /**
     * Deletes every trail point, across every track, captured strictly before {@code cutoff} — the
     * D7 retention prune.
     *
     * @param cutoff points captured before this instant are deleted
     */
    void deleteOlderThan(Instant cutoff);
}
