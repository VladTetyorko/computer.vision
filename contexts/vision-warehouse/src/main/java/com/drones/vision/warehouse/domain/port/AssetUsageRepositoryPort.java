package com.drones.vision.warehouse.domain.port;

import com.drones.vision.kernel.AssetId;
import com.drones.vision.warehouse.domain.model.AssetUsage;
import com.drones.vision.kernel.StreamId;
import com.drones.vision.kernel.UsageId;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Driven port: persist and retrieve asset usage history ("flights"/sessions).
 *
 * <p>A usage row holds a cheap summary (start/last position, sample count)
 * for list rendering; the underlying telemetry trail is stored separately
 * via {@code TelemetryRepositoryPort} and fetched only on demand. Usages are
 * append-only and time-keyed (TimescaleDB-ready): {@link #save(AssetUsage)}
 * upserts by id, so the same usage can be updated in place as it progresses
 * (position/sample-count updates) and finally closed, rather than requiring
 * a new row per update.
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li>{@link #save(AssetUsage)} inserts or updates (upsert by {@link
 *       UsageId}) and returns the persisted usage.</li>
 *   <li>{@link #findById(UsageId)} returns {@link Optional#empty()}, never
 *       {@code null}, when no usage with that id exists.</li>
 *   <li>{@link #findRecentByAsset(AssetId, int)} returns the most recent
 *       usages for the asset, newest first, bounded to at most {@code
 *       limit}; a snapshot, not a live view.</li>
 *   <li>{@link #findRecent(int)} is {@link #findRecentByAsset(AssetId, int)}'s
 *       fleet-wide counterpart (docs/plans/done/NAV-IA-REDESIGN-PLAN.md Wave 4, F8): the
 *       most recent usages across every asset, newest first, bounded to at
 *       most {@code limit}; a snapshot, not a live view.</li>
 *   <li>{@link #findOpenByAsset(AssetId)} returns the asset's currently open
 *       usage ({@code endedAt == null}), if any. At most one usage per asset
 *       is open at a time.</li>
 *   <li>{@link #findByStream(StreamId)} returns the usage that stream opened,
 *       if any. A {@code StreamId} is minted per stream start, so at most one
 *       usage ever carries it; a usage written before the {@code streamId}
 *       field existed, or a stream that never opened one, reads back as
 *       {@link Optional#empty()} &mdash; an absence, not an error.</li>
 * </ul>
 *
 * <h2>Threading</h2>
 * Implementations must be safe for concurrent use: {@code save} may be
 * called frequently while a usage is open (e.g. once per telemetry sample)
 * from the collaborator driving the usage lifecycle, while reads may happen
 * concurrently from control-plane queries.
 */
public interface AssetUsageRepositoryPort {

    /**
     * Inserts or updates a usage.
     *
     * @param usage the usage to persist
     * @return the persisted usage
     */
    AssetUsage save(AssetUsage usage);

    /**
     * Finds a usage by id.
     *
     * @param id the usage id
     * @return the usage, or {@link Optional#empty()} if none exists
     */
    Optional<AssetUsage> findById(UsageId id);

    /**
     * Lists the most recent usages for an asset, newest first.
     *
     * @param assetId the asset id
     * @param limit   maximum number of usages to return; must be positive
     * @return an immutable snapshot of the most recent usages, newest first
     */
    List<AssetUsage> findRecentByAsset(AssetId assetId, int limit);

    /**
     * Lists the most recent usages across every asset, newest first — the fleet-wide "replay
     * library" query (docs/plans/done/NAV-IA-REDESIGN-PLAN.md Wave 4, F8), {@link #findRecentByAsset(AssetId,
     * int)}'s cross-asset counterpart.
     *
     * @param limit maximum number of usages to return; must be positive
     * @return an immutable snapshot of the most recent usages across every asset, newest first
     */
    List<AssetUsage> findRecent(int limit);

    /**
     * Finds the asset's currently open usage, if any.
     *
     * @param assetId the asset id
     * @return the open usage, or {@link Optional#empty()} if none is open
     */
    Optional<AssetUsage> findOpenByAsset(AssetId assetId);

    /**
     * Finds the usage a stream opened &mdash; the answer to "what happened to stream X"
     * (docs/plans/done/STREAM-STATE-PLAN.md &sect;2.6).
     *
     * <p>This exists because a stopped stream is deliberately <b>not</b> a {@code StreamState}: it
     * is absent from the running-stream list and present here, with an {@code endedAt}. Without
     * this lookup that record was reachable only by id, so a caller holding a stream id could
     * observe the stream disappear and had no way to find out what it had been.
     *
     * @param streamId the stream whose usage to find
     * @return the usage that stream opened, or {@link Optional#empty()} if none carries that id
     */
    Optional<AssetUsage> findByStream(StreamId streamId);

    /**
     * Cumulative flight seconds per asset, fleet-wide, in one aggregate query — the "Hours" column
     * on the Vehicles table (docs/plans/active/WAREHOUSE-UX-PLAN.md &sect;3.3, D5; docs/plans/active/
     * WAREHOUSE-UX-CONTEXT.md W4 handoff), computed once per list render rather than once per asset
     * (avoiding the N+1 {@code GET /api/assets/{id}/stats} would otherwise force). Semantics mirror
     * {@code AssetStats#totalFlightSeconds}: an open usage ({@code endedAt == null}) counts its
     * in-progress duration up to "now".
     *
     * <p>Default method, not abstract: adding a fleet-wide aggregate is additive and every real
     * implementation should override it, but a hand-rolled test fake elsewhere in this tree (docs/plans/active/
     * ARCHITECTURE-AUDIT-2026-08-26.md R5's {@code REPOSITORY_PORT_EXEMPTIONS} list — {@code
     * DefaultLabelingServiceTest}'s {@code FakeAssetUsageRepositoryPort}) has no need to know about
     * flight hours and should not have to implement a method it never calls; the default answers
     * honestly with an empty map (unknown, not fabricated), matching {@link
     * java.util.Collections#emptyMap()}'s own "nothing known" contract.
     *
     * @return cumulative flight seconds keyed by asset id; an asset with no usages is simply absent
     *         (its honest value is {@code 0}, not "unknown" — a caller should default a missing key
     *         to {@code 0L}, not {@code null})
     */
    default Map<AssetId, Long> totalFlightSecondsByAsset() {
        return Map.of();
    }
}
