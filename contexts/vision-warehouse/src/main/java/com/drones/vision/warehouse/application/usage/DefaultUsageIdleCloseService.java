package com.drones.vision.warehouse.application.usage;

import com.drones.vision.kernel.Telemetry;
import com.drones.vision.warehouse.domain.model.AssetUsage;
import com.drones.vision.warehouse.domain.model.UsagePhase;
import com.drones.vision.warehouse.domain.port.AssetLiveStatePort;
import com.drones.vision.warehouse.domain.port.AssetUsageRepositoryPort;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * {@link UsageIdleCloseService}'s one implementation: fetch-then-filter over {@link
 * AssetUsageRepositoryPort#findRecent(int)}, the same fetch-then-aggregate posture {@link
 * com.drones.vision.warehouse.application.asset.DefaultAssetStatsService} already documents for its
 * own {@code STATS_FETCH_LIMIT} — cheaper than adding a dedicated "every open usage, fleet-wide"
 * query to the port for a background sweep that runs at most once a minute.
 *
 * <h2>Closing goes through {@link UsageSessionService}, never a repository directly</h2>
 * Per that interface's own javadoc — "the only place in the codebase that constructs or persists an
 * {@code AssetUsage}" — this class never calls {@link AssetUsageRepositoryPort#save} itself; {@link
 * UsageSessionService#close} is the sole write. {@link AssetUsageRepositoryPort#findRecent(int)} is
 * only ever used here as a read.
 *
 * <h2>The closed phase, without depending on flight</h2>
 * {@code UsageTracker}'s own explicit-close path (vision-perception) runs flight's {@code
 * FlightPhaseRule#onSessionClosed} to decide whether a closing usage lands on {@code ABANDONED}
 * (armed, or unresolved since armed) or {@code CLOSED}. Warehouse is the pure leaf of the context
 * dependency graph and may not import that rule, so {@link #idleClosedPhase(UsagePhase)} mirrors its
 * two-branch logic directly against {@link UsagePhase} — the same "mirror by name, no dependency"
 * posture {@link UsagePhase}'s own class javadoc documents for the enum itself.
 */
public final class DefaultUsageIdleCloseService implements UsageIdleCloseService {

    /**
     * Best-effort fetch bound passed to {@link AssetUsageRepositoryPort#findRecent(int)} — a
     * fleet-wide open-usage backlog deeper than this goes unswept until a later cycle catches up
     * with it, the same honest-cap caveat {@code DefaultAssetStatsService.STATS_FETCH_LIMIT}
     * documents for its own fetch-then-aggregate query.
     */
    static final int SWEEP_FETCH_LIMIT = 10_000;

    private final AssetUsageRepositoryPort usageRepository;
    private final AssetLiveStatePort assetLiveStatePort;
    private final UsageSessionService usageSessionService;
    private final IdleUsageCloseSettings settings;
    private final Supplier<Instant> clock;

    public DefaultUsageIdleCloseService(AssetUsageRepositoryPort usageRepository,
                                         AssetLiveStatePort assetLiveStatePort,
                                         UsageSessionService usageSessionService,
                                         IdleUsageCloseSettings settings) {
        this(usageRepository, assetLiveStatePort, usageSessionService, settings, Instant::now);
    }

    /**
     * Test seam: same as the 4-argument constructor, with an explicit "now" supplier so idle/active
     * boundary assertions never depend on wall-clock timing. Production always uses the 4-argument
     * constructor's {@link Instant#now()} default.
     */
    DefaultUsageIdleCloseService(AssetUsageRepositoryPort usageRepository, AssetLiveStatePort assetLiveStatePort,
                                  UsageSessionService usageSessionService, IdleUsageCloseSettings settings,
                                  Supplier<Instant> clock) {
        this.usageRepository = Objects.requireNonNull(usageRepository, "usageRepository must not be null");
        this.assetLiveStatePort = Objects.requireNonNull(assetLiveStatePort, "assetLiveStatePort must not be null");
        this.usageSessionService = Objects.requireNonNull(usageSessionService, "usageSessionService must not be null");
        this.settings = Objects.requireNonNull(settings, "settings must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Override
    public int closeIdleUsages() {
        Instant now = clock.get();
        int closed = 0;
        for (AssetUsage usage : usageRepository.findRecent(SWEEP_FETCH_LIMIT)) {
            if (usage.endedAt() != null) {
                continue; // already closed: not this sweep's concern
            }
            Instant lastActivityAt = lastActivityAt(usage);
            if (Duration.between(lastActivityAt, now).compareTo(settings.idleThreshold()) < 0) {
                continue; // active within the idle threshold (also covers a future lastActivityAt: never close on clock skew)
            }
            usageSessionService.close(usage, idleClosedPhase(usage.phase()), lastActivityAt);
            closed++;
        }
        return closed;
    }

    /**
     * The usage's own last-activity instant: its owning asset's freshest telemetry sample, or {@code
     * startedAt} when none is known — including "the process restarted and the in-memory tracker
     * that held it is gone", see {@link UsageIdleCloseService#closeIdleUsages()}'s own javadoc.
     *
     * <p>Clamped to never read before {@code startedAt}: {@link AssetLiveStatePort#latestTelemetry}
     * answers for the asset as a whole, not this specific usage, and perception's per-asset {@code
     * Tracking} instance outlives any one usage — so a stale sample left over from an earlier,
     * already-closed usage of the same asset must never be mistaken for this usage's own activity.
     */
    private Instant lastActivityAt(AssetUsage usage) {
        Instant lastSampleAt = assetLiveStatePort.latestTelemetry(usage.assetId()).map(Telemetry::at).orElse(null);
        if (lastSampleAt == null || lastSampleAt.isBefore(usage.startedAt())) {
            return usage.startedAt();
        }
        return lastSampleAt;
    }

    /**
     * Mirrors flight's {@code FlightPhaseRule#onSessionClosed} by name only (see this class's own
     * javadoc for why it cannot call it directly): a usage closed while {@code IN_FLIGHT} or {@code
     * LINK_LOST} — the aircraft was, so far as anything last heard, still airborne — lands on {@link
     * UsagePhase#ABANDONED}; every other phase closes normally.
     */
    private static UsagePhase idleClosedPhase(UsagePhase phase) {
        return switch (phase) {
            case IN_FLIGHT, LINK_LOST -> UsagePhase.ABANDONED;
            case PREFLIGHT, POSTFLIGHT, ABANDONED, CLOSED -> UsagePhase.CLOSED;
        };
    }
}
