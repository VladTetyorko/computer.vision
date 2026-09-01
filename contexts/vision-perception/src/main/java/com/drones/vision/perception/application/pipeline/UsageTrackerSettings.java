package com.drones.vision.perception.application.pipeline;

import com.drones.vision.flight.domain.port.TelemetryLiveUpdatePort;
import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.Telemetry;
import com.drones.vision.warehouse.application.maintenance.MaintenanceQuery;

import java.util.Objects;
import java.util.Optional;
import java.util.function.BiConsumer;

/**
 * Every {@link UsageTracker} collaborator beyond its five mandatory ports and telemetry sources,
 * bundled per docs/plans/active/ARCHITECTURE-AUDIT-2026-08-26.md Finding R1 — {@code UsageTracker}
 * previously grew one N-1-arg convenience constructor per wave (ten of them, a 12-arg
 * package-private canonical) purely so pre-existing call sites kept compiling; this record
 * replaces every one of those overloads with a single canonical constructor plus this settings
 * type, matching the {@link UsagePhaseSettings}/{@link UsageSummaryBatchSettings} pattern already
 * established elsewhere in this class.
 *
 * <p>{@link #liveUpdatePublisherPort()} and {@link #telemetryObserver()} are genuinely optional —
 * the old code's own {@code null} meant "feature off" — so they are {@link Optional} here rather
 * than nullable positional arguments; {@link UsageTracker}'s constructor unwraps each via {@code
 * orElse(null)} into its existing private nullable field, so internal behavior is unchanged.
 * Every other field already had its own non-null default ({@link UsageSummaryBatchSettings},
 * {@link UsagePhaseSettings}, {@link UsagePhaseObserver}) or a fixed default value ({@link
 * SupervisedPublisher}'s own backoff constants) and stays a plain non-null field here.
 *
 * @param liveUpdatePublisherPort empty means no live-update announcements (docs/plans/done/REALTIME-PLAN.md §4)
 * @param telemetryObserver       empty means samples are only persisted/announced, never observed;
 *                                when present, must be cheap and must not throw — it runs on the
 *                                telemetry path (docs/plans/done/OPS-CORE-PLAN.md §G)
 * @param sourceInitialBackoffNanos initial reopen backoff for a telemetry source outage
 * @param sourceMaxBackoffNanos     capped reopen backoff for a telemetry source outage
 * @param summaryBatchSettings      how {@code UsageTracker#applySample} coalesces its usage-summary write
 * @param phaseSettings             the clock and {@code FlightPhaseRule} phase-tracking runs against
 * @param usagePhaseObserver        notified on every open/phase-change; never {@code null} — pass
 *                                  {@link UsagePhaseObserver#NOOP} for "do nothing"
 * @param maintenanceQuery          docs/plans/active/ASSET-FLOWS-PLAN.md S1 — consulted by {@link
 *                                  UsageTracker#engage} to refuse an operator-initiated session open
 *                                  on a maintenance-grounded asset; required (no safe no-op default
 *                                  for a safety gate, mirroring {@code ReadinessService} in {@code
 *                                  DefaultManualControlService})
 */
public record UsageTrackerSettings(Optional<TelemetryLiveUpdatePort> liveUpdatePublisherPort,
                                    Optional<BiConsumer<AssetId, Telemetry>> telemetryObserver,
                                    long sourceInitialBackoffNanos, long sourceMaxBackoffNanos,
                                    UsageSummaryBatchSettings summaryBatchSettings, UsagePhaseSettings phaseSettings,
                                    UsagePhaseObserver usagePhaseObserver, MaintenanceQuery maintenanceQuery) {

    public UsageTrackerSettings {
        Objects.requireNonNull(liveUpdatePublisherPort, "liveUpdatePublisherPort must not be null");
        Objects.requireNonNull(telemetryObserver, "telemetryObserver must not be null");
        Objects.requireNonNull(summaryBatchSettings, "summaryBatchSettings must not be null");
        Objects.requireNonNull(phaseSettings, "phaseSettings must not be null");
        Objects.requireNonNull(usagePhaseObserver, "usagePhaseObserver must not be null");
        Objects.requireNonNull(maintenanceQuery, "maintenanceQuery must not be null");
    }

    /**
     * Reproduces the pre-R1 shortest constructor's behavior exactly: no live-update announcements,
     * no telemetry observer, production backoff bounds, immediate summary writes, default phase
     * settings, no phase observer. {@code maintenanceQuery} has no such "pre-R1" default — it is a
     * new required collaborator (ASSET-FLOWS-PLAN S1), so every caller must supply one explicitly.
     */
    public static UsageTrackerSettings defaults(MaintenanceQuery maintenanceQuery) {
        return new UsageTrackerSettings(Optional.empty(), Optional.empty(), SupervisedPublisher.INITIAL_BACKOFF_NANOS,
                SupervisedPublisher.MAX_BACKOFF_NANOS, UsageSummaryBatchSettings.immediate(),
                UsagePhaseSettings.defaults(), UsagePhaseObserver.NOOP, maintenanceQuery);
    }
}
