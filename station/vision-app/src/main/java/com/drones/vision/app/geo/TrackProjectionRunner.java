package com.drones.vision.app.geo;

import com.drones.vision.app.config.properties.VisionGeoProperties;
import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.DeviceId;
import com.drones.vision.kernel.StreamId;
import com.drones.vision.map.application.track.CameraPoseService;
import com.drones.vision.map.application.track.TrackProjectionInput;
import com.drones.vision.map.application.track.TrackProjectionService;
import com.drones.vision.map.domain.model.CameraPose;
import com.drones.vision.perception.application.stream.ActiveStream;
import com.drones.vision.perception.application.stream.StreamService;
import com.drones.vision.perception.domain.model.TrackedObject;
import com.drones.vision.perception.domain.model.VideoFrame;
import com.drones.vision.warehouse.application.asset.AssetDetails;
import com.drones.vision.warehouse.application.asset.AssetService;
import com.drones.vision.warehouse.domain.model.Device;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * The vision-app composition {@code TrackProjectionService} needs but must not itself depend on
 * (docs/plans/active/FIXED-CAMERA-GEO-PLAN.md D2/D7) — resolves each calibrated asset's active
 * stream, calls perception's {@code StreamService#tracks}, and hands {@code (pose,
 * List<TrackedObject>)} to map's {@link TrackProjectionService#project} on a fixed cadence, the
 * O12-recorder precedent (composition here, logic in the context). One instance, self-managed
 * lifecycle ({@code initMethod="start"}/{@code destroyMethod="close"}), mirroring {@code
 * CvChannelSupervisor} — this codebase never uses {@code @Scheduled}/{@code @EnableScheduling}.
 *
 * <h2>Existing only behind the flag (D8)</h2>
 * {@code FixedCameraGeoWiringConfiguration} declares this bean only when {@code
 * vision.geo.fixed-camera.enabled=true}; with it off (the default) this class does not exist and no
 * tick ever runs.
 *
 * <h2>One tick, in order</h2>
 * <ol>
 *   <li>{@link CameraPoseService#list()} — every stored pose. Its asset ids become the new {@link
 *       #hasCameraPose} cache <em>before</em> any projection runs, so D9's demand predicate sees a
 *       calibrated asset as soon as its pose exists, independent of whether that asset currently has
 *       a resolvable stream.</li>
 *   <li>For each pose, resolve the asset's currently active stream (matching one of its device ids
 *       against {@link StreamService#streams()}) and its latest raw frame's dimensions; if either is
 *       absent, this asset is skipped for this tick — no fabricated frame size, no crash.</li>
 *   <li>Otherwise, {@link StreamService#tracks} plus the pose become one {@link
 *       TrackProjectionInput}, handed to {@link TrackProjectionService#project} — per-track expiry
 *       (a track dropping out of the perception track book) is handled inside that call already.</li>
 *   <li><b>Asset-level clearing (D3: "the caller invokes {@code clearAsset} directly — the owning
 *       stream stopped").</b> This runner is that caller: any asset successfully projected last tick
 *       but not this one — its pose was deleted, <em>or</em> its stream became unresolvable — gets an
 *       explicit {@link TrackProjectionService#clearAsset}. A stream unresolvable for even one tick
 *       is treated as stopped (no debounce); see the field javadoc for the gap this specifically
 *       closes.</li>
 *   <li>{@link TrackProjectionService#pruneTrail} on the runner's own cadence (D7 names no separate
 *       prune interval, so this reuses the publish interval — one number, one job).</li>
 * </ol>
 * A single asset's failure (an orphaned pose whose asset was deleted, a transient lookup error) is
 * caught and logged per-asset so it never aborts the rest of the tick — the same "never let one
 * failure take down every later one" contract {@code LiveAndPollDetectionDemand} states explicitly.
 *
 * <h2>A gap this wave found and closes</h2>
 * {@link CameraPoseService#delete} (G2, frozen) is deliberately ignorant of {@code
 * TrackProjectionService} — the two services share no state and {@code CameraPoseService}'s own
 * javadoc documents holding none. Left alone, deleting a camera's pose would leave any of its live
 * tracks on the map forever: nothing else would ever notice the pose is gone and call {@link
 * TrackProjectionService#clearAsset}. The tick-over-tick comparison above is this runner's fix —
 * it is the one place in the system positioned to notice, since it already visits every stored pose
 * once per cycle.
 */
public final class TrackProjectionRunner implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(TrackProjectionRunner.class.getName());
    private static final long CLOSE_AWAIT_SECONDS = 2;

    private final AssetService assetService;
    private final StreamService streamService;
    private final CameraPoseService cameraPoseService;
    private final TrackProjectionService trackProjectionService;
    private final VisionGeoProperties properties;
    private final ScheduledExecutorService scheduler;

    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);

    /**
     * Every asset with a stored pose as of the most recently completed tick — the D9 demand cache
     * {@link #hasCameraPose} reads. {@code volatile}: written only by the scheduler thread, read by
     * arbitrary caller threads via {@code CvWiring#detectionDemandPort}'s predicate.
     */
    private volatile Set<AssetId> posedAssetIds = Set.of();

    /** Assets successfully projected on the previous tick — touched only by the scheduler thread. */
    private Set<AssetId> lastProjectedAssetIds = Set.of();

    public TrackProjectionRunner(AssetService assetService, StreamService streamService,
                                  CameraPoseService cameraPoseService, TrackProjectionService trackProjectionService,
                                  VisionGeoProperties properties) {
        this.assetService = Objects.requireNonNull(assetService, "assetService must not be null");
        this.streamService = Objects.requireNonNull(streamService, "streamService must not be null");
        this.cameraPoseService = Objects.requireNonNull(cameraPoseService, "cameraPoseService must not be null");
        this.trackProjectionService =
                Objects.requireNonNull(trackProjectionService, "trackProjectionService must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.scheduler = Executors.newSingleThreadScheduledExecutor(this::newThread);
    }

    private Thread newThread(Runnable runnable) {
        Thread thread = new Thread(runnable, "track-projection-runner");
        thread.setDaemon(true);
        return thread;
    }

    /** Idempotent. Arms the tick loop — the first tick fires immediately, not after one interval. */
    public void start() {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        long intervalMillis = properties.publishIntervalMillis();
        scheduler.scheduleAtFixedRate(this::tickSafely, 0, intervalMillis, TimeUnit.MILLISECONDS);
    }

    /**
     * Whether {@code assetId} currently has a stored camera pose — the third {@code
     * LiveAndPollDetectionDemand} OR-term (D9). Reads the cache {@link #posedAssetIds} refreshed once
     * per tick; never touches a repository, satisfying the "cheap, never-throws" contract every
     * {@code DetectionDemandPort} consultation needs.
     *
     * @param assetId the asset to check
     * @return {@code true} iff {@code assetId} had a stored pose as of the most recent tick
     */
    public boolean hasCameraPose(AssetId assetId) {
        return posedAssetIds.contains(assetId);
    }

    private void tickSafely() {
        try {
            tick();
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "track-projection tick failed; will retry next cycle", e);
        }
    }

    private void tick() {
        Instant now = Instant.now();
        List<CameraPose> poses = cameraPoseService.list();
        posedAssetIds = poses.stream().map(CameraPose::assetId).collect(Collectors.toUnmodifiableSet());

        Set<AssetId> projectedThisTick = new HashSet<>();
        for (CameraPose pose : poses) {
            if (projectOne(pose, now)) {
                projectedThisTick.add(pose.assetId());
            }
        }

        for (AssetId assetId : lastProjectedAssetIds) {
            if (!projectedThisTick.contains(assetId)) {
                clearSafely(assetId);
            }
        }
        lastProjectedAssetIds = projectedThisTick;

        trackProjectionService.pruneTrail(now.minus(properties.trail().retention()));
    }

    private boolean projectOne(CameraPose pose, Instant now) {
        try {
            Optional<ResolvedStream> resolved = resolveActiveStream(pose.assetId());
            if (resolved.isEmpty()) {
                return false;
            }
            ResolvedStream stream = resolved.get();
            List<TrackedObject> tracks = streamService.tracks(stream.streamId());
            TrackProjectionInput input =
                    new TrackProjectionInput(pose, stream.width(), stream.height(), tracks, now);
            trackProjectionService.project(input);
            return true;
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING,
                    () -> "track projection failed for asset " + pose.assetId().value() + "; skipping this tick", e);
            return false;
        }
    }

    private void clearSafely(AssetId assetId) {
        try {
            trackProjectionService.clearAsset(assetId);
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING,
                    () -> "clearAsset failed for asset " + assetId.value(), e);
        }
    }

    /**
     * Finds {@code assetId}'s currently active stream (any of its devices matched against {@link
     * StreamService#streams()}) and that stream's latest raw, full-resolution frame
     * dimensions. Absent if the asset is unknown (an orphaned pose — its asset was deleted), has no
     * active stream, or has an active stream with no frame decoded yet.
     */
    private Optional<ResolvedStream> resolveActiveStream(AssetId assetId) {
        AssetDetails details;
        try {
            details = assetService.details(assetId);
        } catch (NoSuchElementException e) {
            return Optional.empty();
        }
        Set<DeviceId> deviceIds = details.devices().stream().map(Device::id).collect(Collectors.toSet());
        for (ActiveStream activeStream : streamService.streams()) {
            if (!deviceIds.contains(activeStream.deviceId())) {
                continue;
            }
            Optional<VideoFrame> frame = streamService.latestRawFrame(activeStream.streamId());
            if (frame.isPresent()) {
                return Optional.of(new ResolvedStream(activeStream.streamId(), frame.get().width(),
                        frame.get().height()));
            }
        }
        return Optional.empty();
    }

    /** Idempotent. Stops the tick loop; never touches any port's own lifecycle. */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        scheduler.shutdownNow();
        try {
            if (!scheduler.awaitTermination(CLOSE_AWAIT_SECONDS, TimeUnit.SECONDS)) {
                LOG.log(System.Logger.Level.WARNING,
                        () -> "track-projection-runner did not terminate within " + CLOSE_AWAIT_SECONDS + "s");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private record ResolvedStream(StreamId streamId, int width, int height) {
    }
}
