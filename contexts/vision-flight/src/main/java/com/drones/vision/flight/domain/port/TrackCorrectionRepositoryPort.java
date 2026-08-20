package com.drones.vision.flight.domain.port;

import com.drones.vision.flight.domain.model.TrackCorrection;
import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.UsageId;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Driven port: persist and retrieve {@link TrackCorrection} rows (docs/plans/active/
 * VISUAL-GEO-V2-PLAN.md §3.5/§3.7/D12). Append-only, telemetry-character, high volume (~1 Hz per
 * flying asset) — the {@code track_corrections} table is classified on the excluded side (no audit
 * trigger), the same reasoning V21/V22 already wrote for {@code detection_results}/{@code
 * telemetry_samples}/{@code projected_track_points}.
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li>{@link #save(TrackCorrection)} appends one row; corrections are immutable historical records,
 *       never updated in place.</li>
 *   <li>{@link #findByUsage(UsageId, int)} returns oldest-&gt;newest, bounded to at most {@code
 *       limit} — a snapshot, not a live view (the replay use case).</li>
 *   <li>{@link #findLatest(AssetId)} is the freshest row for one asset, across every usage (the live
 *       cockpit use case).</li>
 *   <li>{@link #deleteOlderThan(Instant)} and {@link #trimUsageToMostRecent(UsageId, int)} are the
 *       runner's own retention/cap mechanisms (§3.6 {@code retention}/{@code max-rows-per-usage});
 *       neither is called by {@code TrackCorrectionService} itself in this wave.</li>
 * </ul>
 *
 * <h2>Threading</h2>
 * Implementations must be safe for concurrent use: {@code save} may be called once per accepted
 * {@code VisualFix} for each open session, concurrently across assets, while the read/prune methods
 * may be called concurrently from control-plane reads and the runner's own cadence.
 */
public interface TrackCorrectionRepositoryPort {

    /**
     * Appends one correction.
     *
     * @param correction the correction to persist
     */
    void save(TrackCorrection correction);

    /**
     * Lists corrections recorded for a usage, oldest first.
     *
     * @param usageId the usage id
     * @param limit   maximum number of corrections to return; must be positive
     * @return an immutable snapshot, oldest-&gt;newest
     */
    List<TrackCorrection> findByUsage(UsageId usageId, int limit);

    /**
     * @param assetId the asset id
     * @return the freshest correction recorded for this asset across every usage, if any
     */
    Optional<TrackCorrection> findLatest(AssetId assetId);

    /**
     * Deletes every correction older than {@code before} ({@code frameAt}), across every asset/usage.
     *
     * @param before the retention horizon
     * @return how many rows were deleted
     */
    int deleteOlderThan(Instant before);

    /**
     * Trims one usage's corrections down to its {@code maxRows} most recent, deleting the rest.
     *
     * @param usageId the usage id
     * @param maxRows how many of the most recent rows to keep; must be positive
     * @return how many rows were deleted
     */
    int trimUsageToMostRecent(UsageId usageId, int maxRows);
}
