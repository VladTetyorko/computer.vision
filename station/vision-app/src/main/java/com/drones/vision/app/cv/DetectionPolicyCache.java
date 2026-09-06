package com.drones.vision.app.cv;

import com.drones.vision.kernel.AssetId;
import com.drones.vision.perception.domain.model.DetectionPolicy;
import com.drones.vision.warehouse.application.asset.AssetService;
import com.drones.vision.warehouse.application.asset.AssetSummary;

import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * The vision-app composition {@code CvWiring#detectionPolicyPort} needs but {@code
 * vision-perception} must not itself depend on (docs/plans/active/ALWAYS-ON-FLOW-PLAN.md wave D1,
 * mirroring {@code TrackProjectionRunner}'s own D9 caching precedent) — re-lists every asset from
 * {@link AssetService} once per tick and caches which ones have opted into {@link
 * DetectionPolicy#ALWAYS} via their {@code attributes} map, so {@code DetectionPolicyPort}'s
 * per-poll consultation ({@code DefaultStreamService}'s demand-poll task, once per stream every
 * {@code StreamPipelineSettings#detectionDemandPollInterval()}) is a {@code volatile} set read,
 * never a repository call — the same "cheap, never-throws" contract every {@code
 * DetectionDemandPort}/{@code DetectionPolicyPort} consultation needs. One instance, self-managed
 * lifecycle ({@code initMethod="start"}/{@code destroyMethod="close"}), the same shape {@code
 * TrackProjectionRunner}/{@code CvChannelSupervisor} already use — this codebase never uses {@code
 * @Scheduled}/{@code @EnableScheduling}.
 *
 * <h2>Staleness</h2>
 * Up to one refresh interval ({@code VisionCvProperties.Policy#refreshInterval()}, default 15s)
 * stale: an asset just switched to {@code ALWAYS} can wait up to that long before this cache's
 * answer reflects it, and a stream demand-polled on {@code VisionCvProperties.Demand#pollInterval()}
 * (2s default) can therefore see it up to ~15s late too. Acceptable because the attribute is an
 * operator's own deliberate, infrequent choice (docs/plans/active/ALWAYS-ON-FLOW-PLAN.md wave D1) —
 * not the sub-second reactivity viewer-presence gating ({@code DetectionDemandPort}) needs — in
 * exchange for never blocking a scheduler thread on a repository call per stream per tick.
 *
 * <h2>Fail-closed, deliberately the opposite direction from the demand cache's own precedent</h2>
 * A refresh failure ({@link AssetService#assets(boolean)} throws) leaves the previous snapshot in
 * place — the same "hold the last good answer" choice {@code TrackProjectionRunner} makes for
 * camera poses — and before the first successful tick, or for an asset this cache has never
 * observed, {@link #alwaysOn} reads {@code false} (equivalent to {@code DetectionPolicy.ON_VIEW}),
 * never {@code true}: unlike viewer demand's fail-open {@code true} (existing behavior must not
 * stop), a policy lookup failure must not silently grant every asset free, uncapped inference — see
 * {@link DetectionPolicy}'s own javadoc for the capacity ceiling this protects.
 */
public final class DetectionPolicyCache implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(DetectionPolicyCache.class.getName());
    private static final long CLOSE_AWAIT_SECONDS = 2;

    private final AssetService assetService;
    private final Duration refreshInterval;
    private final ScheduledExecutorService scheduler;

    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);

    /**
     * Every asset whose {@code attributes} map resolved to {@link DetectionPolicy#ALWAYS} as of the
     * most recently completed tick — the cache {@link #alwaysOn} reads. {@code volatile}: written
     * only by the scheduler thread, read by arbitrary caller threads via {@code
     * CvWiring#detectionPolicyPort}'s lambda.
     */
    private volatile Set<AssetId> alwaysOnAssetIds = Set.of();

    public DetectionPolicyCache(AssetService assetService, Duration refreshInterval) {
        this.assetService = Objects.requireNonNull(assetService, "assetService must not be null");
        this.refreshInterval = Objects.requireNonNull(refreshInterval, "refreshInterval must not be null");
        this.scheduler = Executors.newSingleThreadScheduledExecutor(this::newThread);
    }

    private Thread newThread(Runnable runnable) {
        Thread thread = new Thread(runnable, "detection-policy-cache");
        thread.setDaemon(true);
        return thread;
    }

    /** Idempotent. Arms the tick loop — the first tick fires immediately, not after one interval. */
    public void start() {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        long intervalMillis = refreshInterval.toMillis();
        scheduler.scheduleAtFixedRate(this::tickSafely, 0, intervalMillis, TimeUnit.MILLISECONDS);
    }

    /**
     * Whether {@code assetId} currently has {@link DetectionPolicy#ALWAYS} set. Reads the cache
     * {@link #alwaysOnAssetIds} refreshed once per tick; never touches {@link AssetService}, per
     * this class's own javadoc.
     *
     * @param assetId the asset to check, or {@code null}
     * @return {@code true} iff {@code assetId} is non-null and had {@code ALWAYS} set as of the most
     *         recent tick
     */
    public boolean alwaysOn(AssetId assetId) {
        return assetId != null && alwaysOnAssetIds.contains(assetId);
    }

    private void tickSafely() {
        try {
            tick();
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING,
                    "detection-policy cache refresh failed; keeping previous snapshot", e);
        }
    }

    private void tick() {
        alwaysOnAssetIds = assetService.assets(false).stream()
                .filter(this::isAlwaysOn)
                .map(summary -> summary.asset().id())
                .collect(Collectors.toUnmodifiableSet());
    }

    private boolean isAlwaysOn(AssetSummary summary) {
        String rawValue = summary.asset().attributes().get(DetectionPolicy.ATTRIBUTE_KEY);
        return DetectionPolicy.fromAttributeValue(rawValue) == DetectionPolicy.ALWAYS;
    }

    /** Idempotent. Stops the tick loop; never touches {@link AssetService}'s own lifecycle. */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        scheduler.shutdownNow();
        try {
            if (!scheduler.awaitTermination(CLOSE_AWAIT_SECONDS, TimeUnit.SECONDS)) {
                LOG.log(System.Logger.Level.WARNING,
                        () -> "detection-policy-cache did not terminate within " + CLOSE_AWAIT_SECONDS + "s");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
