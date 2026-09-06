package com.drones.vision.app.usage;

import com.drones.vision.app.config.properties.VisionTelemetryProperties;
import com.drones.vision.kernel.AssetId;
import com.drones.vision.perception.application.pipeline.UsageTracker;
import com.drones.vision.warehouse.application.asset.AssetService;
import com.drones.vision.warehouse.application.asset.AssetSummary;

import java.time.Instant;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Keeps every in-service asset's telemetry claimed, continuously, so a link is live because the
 * aircraft exists — not because somebody is watching it
 * (docs/plans/active/ALWAYS-ON-FLOW-PLAN.md wave A1).
 *
 * <p>The {@code vision-app} composition {@link UsageTracker} needs but must not itself depend on,
 * following {@link UsageIdleCloseRunner}'s exact template: policy and scheduling here, mechanism in
 * the context module. This codebase never uses {@code @Scheduled}/{@code @EnableScheduling}.
 *
 * <h2>Why a reconciler rather than an event listener</h2>
 * The set of assets that should be claimed changes for reasons this class cannot observe: an asset
 * is registered, deactivated, deleted, or has a telemetry-capable device attached or removed. A
 * periodic diff converges on all of them with one code path, and — unlike an event subscription —
 * it also self-heals after a missed event, a restart, or a source that failed to open on an earlier
 * tick. {@link UsageTracker#pinTelemetry} is idempotent precisely so this can run on every sweep.
 *
 * <h2>What one sweep does</h2>
 * Lists non-deleted assets, partitions them by {@code Asset#isActive()}, pins the active ones and
 * unpins everything it had pinned that no longer qualifies. Asset-level policy only: <em>which</em>
 * of an asset's devices can actually carry telemetry is {@code UsageTracker}'s business, and it
 * skips devices without the {@code TELEMETRY} capability itself — so pinning a video-only asset is
 * a cheap no-op rather than something this class must predict.
 *
 * <h2>What it deliberately does not do</h2>
 * Pinning opens a telemetry subscription; it never opens an {@code AssetUsage}. A flight stays
 * explicit — an operator engaging, or a device starting a video stream. This runner makes the
 * <em>link</em> always-on, not the <em>flight</em>, and that distinction is the whole point of
 * wave A: telemetry stops being a side effect of somebody looking.
 *
 * <p>Unpinning is likewise conservative: {@link UsageTracker#unpinTelemetry} tears a subscription
 * down only if nothing else still needs it, so an asset deactivated mid-flight keeps its telemetry
 * until the flight itself ends.
 */
public final class TelemetryPinRunner implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(TelemetryPinRunner.class.getName());
    private static final long CLOSE_AWAIT_SECONDS = 2;

    private final AssetService assetService;
    private final UsageTracker usageTracker;
    private final VisionTelemetryProperties properties;
    private final ScheduledExecutorService scheduler;

    /**
     * What this runner currently believes it has pinned. Held here rather than asked of {@link
     * UsageTracker} so that unpinning stays this class's own responsibility: the tracker's pin flag
     * is a mechanism, and which assets policy covers is a question only this class answers.
     */
    private final Set<AssetId> pinned = ConcurrentHashMap.newKeySet();

    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicReference<Instant> lastSweepAt = new AtomicReference<>();

    public TelemetryPinRunner(AssetService assetService, UsageTracker usageTracker,
                               VisionTelemetryProperties properties) {
        this.assetService = Objects.requireNonNull(assetService, "assetService must not be null");
        this.usageTracker = Objects.requireNonNull(usageTracker, "usageTracker must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.scheduler = Executors.newSingleThreadScheduledExecutor(this::newThread);
    }

    private Thread newThread(Runnable runnable) {
        Thread thread = new Thread(runnable, "telemetry-pin-runner");
        thread.setDaemon(true);
        return thread;
    }

    /**
     * Idempotent. The first sweep fires immediately, so a restart re-claims links without waiting a
     * full period.
     *
     * <p>The disabled branch lives here rather than in the wiring, matching {@code
     * IdleStreamReaper#start()}'s idiom: the policy is expressed by the property, never by omitting
     * the bean. That keeps "always-on telemetry is off" a readable state of a present object rather
     * than an absent one.
     */
    public void start() {
        if (!properties.alwaysOn().enabled() || !started.compareAndSet(false, true)) {
            return;
        }
        long periodMillis = properties.alwaysOn().sweepInterval().toMillis();
        scheduler.scheduleAtFixedRate(this::sweepSafely, 0, periodMillis, TimeUnit.MILLISECONDS);
    }

    /**
     * One reconciliation pass. Package-private so a test can drive a single deterministic sweep
     * rather than racing the scheduler.
     */
    void sweepSafely() {
        try {
            Set<AssetId> shouldBePinned = new HashSet<>();
            for (AssetSummary summary : assetService.assets()) {
                if (summary.asset().isActive()) {
                    shouldBePinned.add(summary.asset().id());
                }
            }
            for (AssetId assetId : shouldBePinned) {
                usageTracker.pinTelemetry(assetId); // idempotent; also picks up newly attached devices
            }
            // Iterate a copy: unpinning mutates `pinned`, and a deleted asset is simply absent from
            // the listing above rather than reported as an event.
            for (AssetId assetId : Set.copyOf(pinned)) {
                if (!shouldBePinned.contains(assetId)) {
                    usageTracker.unpinTelemetry(assetId);
                    pinned.remove(assetId);
                }
            }
            pinned.addAll(shouldBePinned);
            lastSweepAt.set(Instant.now());
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "telemetry-pin sweep failed; will retry next cycle", e);
        }
    }

    /**
     * When this runner last completed a full sweep without throwing — {@code null} before the
     * first. Deliberately not stamped for a sweep that failed partway: a stale timestamp is a
     * readable symptom, whereas a fresh one on a half-done sweep would hide the failure.
     */
    public Instant lastSweepAt() {
        return lastSweepAt.get();
    }

    /** How many assets this runner currently holds pinned — the cheap answer to "is always-on telemetry actually on?". */
    public int pinnedCount() {
        return pinned.size();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        scheduler.shutdownNow();
        try {
            if (!scheduler.awaitTermination(CLOSE_AWAIT_SECONDS, TimeUnit.SECONDS)) {
                LOG.log(System.Logger.Level.WARNING, "telemetry-pin runner did not stop within "
                        + CLOSE_AWAIT_SECONDS + "s");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
