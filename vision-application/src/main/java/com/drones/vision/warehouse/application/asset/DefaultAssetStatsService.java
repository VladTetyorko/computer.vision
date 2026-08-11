package com.drones.vision.warehouse.application.asset;

import com.drones.vision.kernel.AssetId;
import com.drones.vision.warehouse.domain.model.AssetUsage;
import com.drones.vision.kernel.Telemetry;
import com.drones.vision.warehouse.domain.port.AssetUsageRepositoryPort;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import com.drones.vision.warehouse.application.fleet.DefaultFleetSummaryService;
import com.drones.vision.warehouse.application.fleet.FleetSummaryService;
import com.drones.vision.perception.application.pipeline.UsageTracker;

/**
 * {@link AssetStatsService}'s one implementation: fetch-then-aggregate over {@link
 * AssetUsageRepositoryPort#findRecentByAsset(AssetId, int)}, mirroring {@link
 * com.drones.vision.events.application.DefaultReplayService}'s own fetch-then-aggregate precedent (see its javadoc's {@code
 * TELEMETRY_FETCH_LIMIT} discussion for the same honest-cap reasoning applied here to {@link
 * #STATS_FETCH_LIMIT}).
 *
 * <h2>Aggregation rules (docs/plans/done/ASSET-MANAGER-PAGE-PLAN.md, Wave A's frozen contract)</h2>
 * <ul>
 *   <li>Each usage's duration is {@code (endedAt != null ? endedAt : now) - startedAt}, floored at
 *       zero — defensive against the "now" clock reading before an open usage's own {@code
 *       startedAt}, which clock skew (or, in tests, a fixed injected clock) can otherwise produce
 *       as a negative {@link Duration}.</li>
 *   <li>An open usage ({@code endedAt == null}) counts its in-progress duration toward {@link
 *       AssetStats#totalFlightSeconds()} up to "now", and sets {@link
 *       AssetStats#flightInProgress()}.</li>
 *   <li>{@link AssetStats#avgFlightSeconds()} averages <b>closed</b> usages' durations only — an
 *       open usage has no final duration to average in — and is {@code null} when there are no
 *       closed usages, even if an open one exists.</li>
 *   <li>{@link AssetStats#firstFlownAt()} is the minimum {@code startedAt}; {@link
 *       AssetStats#lastFlownAt()} is the maximum "activity instant", where a closed usage
 *       contributes its {@code endedAt} and an open usage contributes its own {@code startedAt}
 *       (not "now" — "now" only feeds the running total, not this timestamp). Both are {@code
 *       null} when there are no usages.</li>
 * </ul>
 *
 * <h2>Battery</h2>
 * {@link AssetStats#lastKnownBatteryPercent()} reuses exactly the source {@link
 * DefaultFleetSummaryService} derives its own {@code batteryPercent} from — {@link
 * UsageTracker#latestTelemetry(AssetId)}, {@code null} when the asset has never reported
 * telemetry — rounded to the nearest whole percent here purely because this endpoint's own frozen
 * wire contract wants an {@code Integer} KPI figure where {@link FleetSummaryService}'s own
 * {@code AssetAttention} carries the raw {@code Double} reading.
 *
 * <h2>No existence check</h2>
 * Deliberately never validates that {@code assetId} is a real, known asset — see {@link
 * AssetStatsService#statsFor(AssetId)}'s own javadoc. An unknown asset simply has zero usages and
 * no telemetry, so it aggregates to the same all-zero/all-{@code null} {@link AssetStats} a
 * genuinely empty flight history would. {@code vision-api}'s controller 404s an unknown asset
 * itself, before ever calling this method.
 */
public final class DefaultAssetStatsService implements AssetStatsService {

    /**
     * Best-effort fetch bound passed to {@link AssetUsageRepositoryPort#findRecentByAsset(AssetId,
     * int)} — mirrors {@link com.drones.vision.events.application.DefaultReplayService#TELEMETRY_FETCH_LIMIT}'s own honest-cap caveat:
     * an asset with more than {@value #STATS_FETCH_LIMIT} recorded flights under-reports (both the
     * counters and, since {@code findRecentByAsset} returns newest-first, biased toward its
     * <b>most recent</b> history rather than a random sample) until this port grows a real
     * server-side aggregate query. Not attempted here — documented, not fixed, same posture as the
     * replay-service precedent this mirrors.
     */
    static final int STATS_FETCH_LIMIT = 10_000;

    private final AssetUsageRepositoryPort usageRepository;
    private final UsageTracker usageTracker;
    private final Supplier<Instant> clock;
    private final int statsFetchLimit;

    public DefaultAssetStatsService(AssetUsageRepositoryPort usageRepository, UsageTracker usageTracker) {
        this(usageRepository, usageTracker, Instant::now, STATS_FETCH_LIMIT);
    }

    /**
     * Same as the 2-argument constructor, plus an explicit fetch bound (docs/plans/active/LAYERING-REFACTOR-PLAN.md
     * &sect;1.3 config extraction, {@code vision.application.stats.fetch-limit}) instead of {@link
     * #STATS_FETCH_LIMIT}.
     */
    public DefaultAssetStatsService(AssetUsageRepositoryPort usageRepository, UsageTracker usageTracker,
                                     int statsFetchLimit) {
        this(usageRepository, usageTracker, Instant::now, statsFetchLimit);
    }

    /**
     * Test seam: same as the 2-argument constructor, with an explicit "now" supplier so a test can
     * assert an open usage's in-progress duration deterministically instead of racing the real
     * clock. Production always uses the 2-argument constructor's {@link Instant#now()} default.
     */
    DefaultAssetStatsService(AssetUsageRepositoryPort usageRepository, UsageTracker usageTracker,
                              Supplier<Instant> clock) {
        this(usageRepository, usageTracker, clock, STATS_FETCH_LIMIT);
    }

    /** Test/wiring seam: same as the 2-argument constructor, with both an explicit clock and fetch bound. */
    DefaultAssetStatsService(AssetUsageRepositoryPort usageRepository, UsageTracker usageTracker,
                              Supplier<Instant> clock, int statsFetchLimit) {
        this.usageRepository = Objects.requireNonNull(usageRepository, "usageRepository must not be null");
        this.usageTracker = Objects.requireNonNull(usageTracker, "usageTracker must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.statsFetchLimit = statsFetchLimit;
    }

    @Override
    public AssetStats statsFor(AssetId assetId) {
        Objects.requireNonNull(assetId, "assetId must not be null");
        List<AssetUsage> usages = usageRepository.findRecentByAsset(assetId, statsFetchLimit);
        Instant now = clock.get();

        long totalSeconds = 0;
        Instant firstFlownAt = null;
        Instant lastFlownAt = null;
        long closedSecondsSum = 0;
        int closedCount = 0;
        boolean flightInProgress = false;

        for (AssetUsage usage : usages) {
            boolean open = usage.endedAt() == null;
            Instant end = open ? now : usage.endedAt();
            long seconds = Math.max(0, Duration.between(usage.startedAt(), end).getSeconds());
            totalSeconds += seconds;

            if (firstFlownAt == null || usage.startedAt().isBefore(firstFlownAt)) {
                firstFlownAt = usage.startedAt();
            }
            Instant activity = open ? usage.startedAt() : usage.endedAt();
            if (lastFlownAt == null || activity.isAfter(lastFlownAt)) {
                lastFlownAt = activity;
            }

            if (open) {
                flightInProgress = true;
            } else {
                closedSecondsSum += seconds;
                closedCount++;
            }
        }

        Long avgFlightSeconds =
                closedCount == 0 ? null : Math.round(closedSecondsSum / (double) closedCount);

        return new AssetStats(totalSeconds, usages.size(), firstFlownAt, lastFlownAt, avgFlightSeconds,
                lastKnownBatteryPercent(assetId), flightInProgress);
    }

    private Integer lastKnownBatteryPercent(AssetId assetId) {
        Double batteryPercent = usageTracker.latestTelemetry(assetId).map(Telemetry::batteryPercent).orElse(null);
        return batteryPercent == null ? null : (int) Math.round(batteryPercent);
    }
}
