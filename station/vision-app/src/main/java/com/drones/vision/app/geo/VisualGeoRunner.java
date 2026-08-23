package com.drones.vision.app.geo;

import com.drones.vision.app.config.properties.VisionGeoVisualProperties;
import com.drones.vision.flight.application.TrackCorrectionService;
import com.drones.vision.flight.domain.port.TrackCorrectionRepositoryPort;
import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.DeviceId;
import com.drones.vision.kernel.StreamId;
import com.drones.vision.kernel.Telemetry;
import com.drones.vision.kernel.UsageId;
import com.drones.vision.kernel.VisualFix;
import com.drones.vision.perception.application.geo.GeolocationSessionService;
import com.drones.vision.perception.application.pipeline.UsageTracker;
import com.drones.vision.perception.application.stream.ActiveStream;
import com.drones.vision.perception.application.stream.StreamService;
import com.drones.vision.perception.domain.model.GeoSessionConfig;
import com.drones.vision.warehouse.application.asset.AssetDetails;
import com.drones.vision.warehouse.application.asset.AssetService;
import com.drones.vision.warehouse.application.asset.AssetSummary;
import com.drones.vision.warehouse.domain.model.AssetUsage;
import com.drones.vision.warehouse.domain.model.Device;
import com.drones.vision.warehouse.domain.port.AssetUsageRepositoryPort;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * The vision-app composition layer D7 assigns visual geolocation to (docs/plans/active/
 * VISUAL-GEO-V2-PLAN.md D7's own table row, "Composition (open/close sessions, pump telemetry, route
 * fixes)") — reconciles which flying assets need a localization session, pumps their telemetry into
 * an open session, routes each session's {@link VisualFix} results into {@code
 * TrackCorrectionService#submit}, and prunes on its own cadence. Perception never names a flight
 * type and flight never names a perception type; this class is the one place that names both — the
 * exact {@link com.drones.vision.map.application.track.TrackProjectionService}/{@code
 * GeofenceMonitor} precedent: logic in the context, composition in the legal composer. One instance,
 * self-managed lifecycle ({@code initMethod="start"}/{@code destroyMethod="close"}), the same
 * {@code TrackProjectionRunner} template this codebase already follows (never {@code @Scheduled}).
 *
 * <h2>Existing only behind the flag (D9)</h2>
 * {@code VisualGeoWiringConfiguration} declares this bean only when {@code
 * vision.geo.visual.enabled=true}; with it off (the default) this class does not exist, no gRPC geo
 * session is ever opened, and no {@code geo} SSE event is ever published.
 *
 * <h2>Demand — which assets get a session</h2>
 * An asset is in this tick's desired set iff it has a currently open {@link AssetUsage} ({@link
 * AssetUsageRepositoryPort#findOpenByAsset}) <b>and</b> a resolvable active stream (one of its
 * devices matched against {@link StreamService#streams()}, with at least one decoded raw frame as
 * the liveness signal — mirroring {@code TrackProjectionRunner#resolveActiveStream}, minus the frame
 * dimensions that class needs and this one does not: D2's pull transport means only the mediamtx
 * path matters here, never a decoded frame). The plan leaves the exact demand definition to this
 * wave (D7's table names the composition responsibility, not the predicate) — this is the narrowest
 * honest reading: never open a session for an asset with no active flight, and never for one with no
 * resolvable video source to point the worker at.
 *
 * <h2>One tick, in order</h2>
 * <ol>
 *   <li>Compute this tick's desired asset set per the predicate above.</li>
 *   <li>For each desired asset: open a session if none is open yet for its current stream ({@link
 *       GeolocationSessionService#start}, building {@code sourceUrl} as {@code rtsp-base + "/" +
 *       streamId}, O12); pump its latest known telemetry ({@link UsageTracker#latestTelemetry}, withheld
 *       if older than {@code telemetry-max-age} per D2's "restated on every message, never sent
 *       stale" doctrine); and submit this tick's latest received {@link VisualFix}, if any arrived
 *       since the previous tick, to {@link TrackCorrectionService#submit} — <b>at most one row per
 *       tick per asset</b> (O8: "sampled — one row per {@code runner-interval-millis}, not one per
 *       keyframe"), never one row per worker-emitted fix.</li>
 *   <li>Any asset open last tick but not desired this tick gets its session closed ({@link
 *       GeolocationSessionService#stop}) — the same tick-over-tick set-difference {@code
 *       TrackProjectionRunner} uses for {@code clearAsset}.</li>
 *   <li>{@link TrackCorrectionService#prune} on this runner's own cadence, plus a per-open-usage
 *       {@link TrackCorrectionRepositoryPort#trimUsageToMostRecent} cap — {@code
 *       TrackCorrectionService} itself calls neither (see its own interface javadoc: "not called by
 *       {@code TrackCorrectionService} itself in this wave"), so retention is entirely this
 *       composition layer's job.</li>
 * </ol>
 * A single asset's failure (an orphaned usage whose asset was deleted, a transient session-open
 * error) is caught and logged per-asset so it never aborts the rest of the tick — the same contract
 * {@code TrackProjectionRunner}/{@code LiveAndPollDetectionDemand} already state explicitly.
 *
 * <h2>Sampling a session's fix stream (O8)</h2>
 * {@link #ensureSessionOpen} subscribes a {@link LatestFixSubscriber} to the session's publisher at
 * open time — an unbounded-demand subscriber that simply holds the most recently received {@link
 * VisualFix} (CLAUDE.md rule 9: newest wins, replacing whatever arrived before it was consumed).
 * Each tick, {@link #submitLatestFix} takes-and-clears whatever is currently held; if nothing arrived
 * since the previous tick, nothing is submitted for that asset this tick — never a duplicate, never a
 * fabricated "no update" row.
 *
 * <h2>A terminated session is not an open one (H8)</h2>
 * A cv-service bounce ends the underlying bidi call, which {@code GeolocationSession#failAndDrop}
 * surfaces as {@link Flow.Subscriber#onError} after dropping itself from the adapter's own session
 * map. H7 found that this runner then wedged permanently: the dead {@link OpenSession} stayed in
 * {@link #openSessions}, so {@link #ensureSessionOpen}'s "already open for this stream" early return
 * kept firing forever and geolocation ended silently even after {@code CvChannelSupervisor} recovered
 * the channel (docs/plans/done/VISUAL-GEO-V2-PLAN.md §9.11, defect 1). {@link LatestFixSubscriber}
 * now latches its own termination, and a terminated subscriber makes {@link #ensureSessionOpen} treat
 * the session as closed: it runs {@link #closeSafely} — which clears {@code
 * GeolocationSessionService}'s own open-session bookkeeping too, so a fresh {@code start} is legal
 * again — and opens a new one in the same tick. No backoff is coded here on purpose: the reopen
 * attempt goes through {@code GrpcPulledGeolocationPort#open}, which is already gated fail-fast by
 * {@code CvChannelSupervisor#available()}, so this tick cadence <em>is</em> the retry cadence and the
 * session only actually reopens once the supervisor reports the channel healthy.
 */
public final class VisualGeoRunner implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(VisualGeoRunner.class.getName());
    private static final long CLOSE_AWAIT_SECONDS = 2;

    private final AssetService assetService;
    private final AssetUsageRepositoryPort assetUsageRepositoryPort;
    private final StreamService streamService;
    private final UsageTracker usageTracker;
    private final GeolocationSessionService geolocationSessionService;
    private final TrackCorrectionService trackCorrectionService;
    private final TrackCorrectionRepositoryPort trackCorrectionRepositoryPort;
    private final VisionGeoVisualProperties properties;
    private final ScheduledExecutorService scheduler;

    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);

    /** Open sessions as of the most recently completed tick — touched only by the scheduler thread. */
    private final Map<AssetId, OpenSession> openSessions = new HashMap<>();

    public VisualGeoRunner(AssetService assetService, AssetUsageRepositoryPort assetUsageRepositoryPort,
                            StreamService streamService, UsageTracker usageTracker,
                            GeolocationSessionService geolocationSessionService,
                            TrackCorrectionService trackCorrectionService,
                            TrackCorrectionRepositoryPort trackCorrectionRepositoryPort,
                            VisionGeoVisualProperties properties) {
        this.assetService = Objects.requireNonNull(assetService, "assetService must not be null");
        this.assetUsageRepositoryPort =
                Objects.requireNonNull(assetUsageRepositoryPort, "assetUsageRepositoryPort must not be null");
        this.streamService = Objects.requireNonNull(streamService, "streamService must not be null");
        this.usageTracker = Objects.requireNonNull(usageTracker, "usageTracker must not be null");
        this.geolocationSessionService =
                Objects.requireNonNull(geolocationSessionService, "geolocationSessionService must not be null");
        this.trackCorrectionService =
                Objects.requireNonNull(trackCorrectionService, "trackCorrectionService must not be null");
        this.trackCorrectionRepositoryPort = Objects.requireNonNull(trackCorrectionRepositoryPort,
                "trackCorrectionRepositoryPort must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.scheduler = Executors.newSingleThreadScheduledExecutor(this::newThread);
    }

    private Thread newThread(Runnable runnable) {
        Thread thread = new Thread(runnable, "visual-geo-runner");
        thread.setDaemon(true);
        return thread;
    }

    /** Idempotent. Arms the tick loop — the first tick fires immediately, not after one interval. */
    public void start() {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        long intervalMillis = properties.runnerIntervalMillis();
        scheduler.scheduleAtFixedRate(this::tickSafely, 0, intervalMillis, TimeUnit.MILLISECONDS);
    }

    private void tickSafely() {
        try {
            tick();
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "visual-geo tick failed; will retry next cycle", e);
        }
    }

    private void tick() {
        Instant now = Instant.now();
        Set<AssetId> desired = new HashSet<>();

        for (AssetSummary summary : assetService.assets(false)) {
            AssetId assetId = summary.asset().id();
            try {
                tickOneAsset(assetId, now, desired);
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING,
                        () -> "visual-geo tick failed for asset " + assetId.value() + "; skipping this tick", e);
            }
        }

        for (AssetId assetId : Set.copyOf(openSessions.keySet())) {
            if (!desired.contains(assetId)) {
                closeSafely(assetId);
            }
        }

        trackCorrectionService.prune(now.minus(properties.retention()));
    }

    private void tickOneAsset(AssetId assetId, Instant now, Set<AssetId> desired) {
        Optional<AssetUsage> usage = assetUsageRepositoryPort.findOpenByAsset(assetId);
        if (usage.isEmpty()) {
            return;
        }
        Optional<StreamId> streamId = resolveActiveStream(assetId);
        if (streamId.isEmpty()) {
            return;
        }
        desired.add(assetId);
        UsageId usageId = usage.get().id();
        ensureSessionOpen(assetId, usageId, streamId.get());
        pumpTelemetry(assetId, streamId.get(), now);
        submitLatestFix(assetId, usageId);
        trackCorrectionRepositoryPort.trimUsageToMostRecent(usageId, properties.maxRowsPerUsage());
    }

    /**
     * Finds {@code assetId}'s currently active stream (any of its devices matched against {@link
     * StreamService#streams()}), requiring at least one decoded raw frame as the liveness signal —
     * see class javadoc for why no frame dimensions are needed here, unlike {@code
     * TrackProjectionRunner}'s own version of this method.
     */
    private Optional<StreamId> resolveActiveStream(AssetId assetId) {
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
            if (streamService.latestRawFrame(activeStream.streamId()).isPresent()) {
                return Optional.of(activeStream.streamId());
            }
        }
        return Optional.empty();
    }

    private void ensureSessionOpen(AssetId assetId, UsageId usageId, StreamId streamId) {
        OpenSession existing = openSessions.get(assetId);
        if (existing != null && existing.streamId().equals(streamId) && !existing.subscriber().terminated()) {
            return;
        }
        if (existing != null) {
            // Either the asset's active stream changed under us, or the session's own fix stream has
            // terminated (a cv-service bounce). Both are closed honestly rather than kept in the map:
            // keeping a stale one would either pump telemetry at the wrong mediamtx path or -- the H8
            // defect -- make this method's early return end geolocation permanently and silently.
            closeSafely(assetId);
        }
        try {
            URI sourceUrl = URI.create(properties.rtspBase().toString() + "/" + streamId.value());
            GeoSessionConfig config = properties.defaultSessionConfig();
            Flow.Publisher<VisualFix> publisher = geolocationSessionService.start(streamId, sourceUrl, config);
            LatestFixSubscriber subscriber = new LatestFixSubscriber(streamId);
            publisher.subscribe(subscriber);
            openSessions.put(assetId, new OpenSession(streamId, subscriber));
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING,
                    () -> "failed to open a visual-geo session for asset " + assetId.value(), e);
        }
    }

    private void pumpTelemetry(AssetId assetId, StreamId streamId, Instant now) {
        Optional<Telemetry> telemetry = usageTracker.latestTelemetry(assetId);
        if (telemetry.isEmpty()) {
            return;
        }
        Telemetry sample = telemetry.get();
        Duration age = Duration.between(sample.at(), now);
        if (age.isNegative() || age.compareTo(properties.telemetryMaxAge()) > 0) {
            return; // D2: withhold rather than restate stale telemetry
        }
        geolocationSessionService.telemetry(streamId, sample);
    }

    private void submitLatestFix(AssetId assetId, UsageId usageId) {
        OpenSession session = openSessions.get(assetId);
        if (session == null) {
            return;
        }
        VisualFix fix = session.subscriber().takeLatest();
        if (fix == null) {
            return; // O8: nothing new since the previous tick -- no row this tick
        }
        Telemetry rawAtFrameTime = usageTracker.latestTelemetry(assetId).orElse(null);
        trackCorrectionService.submit(assetId, usageId, fix, rawAtFrameTime);
    }

    private void closeSafely(AssetId assetId) {
        OpenSession session = openSessions.remove(assetId);
        if (session == null) {
            return;
        }
        try {
            geolocationSessionService.stop(session.streamId());
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING,
                    () -> "closing the visual-geo session failed for asset " + assetId.value(), e);
        }
    }

    /** Idempotent. Stops the tick loop and every open session; never touches the shared gRPC channel. */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        scheduler.shutdownNow();
        try {
            if (!scheduler.awaitTermination(CLOSE_AWAIT_SECONDS, TimeUnit.SECONDS)) {
                LOG.log(System.Logger.Level.WARNING,
                        () -> "visual-geo-runner did not terminate within " + CLOSE_AWAIT_SECONDS + "s");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        for (AssetId assetId : Set.copyOf(openSessions.keySet())) {
            closeSafely(assetId);
        }
    }

    private record OpenSession(StreamId streamId, LatestFixSubscriber subscriber) {
    }

    /**
     * An unbounded-demand {@link Flow.Subscriber} that holds only the most recently received {@link
     * VisualFix} — see class javadoc, "Sampling a session's fix stream (O8)".
     */
    private static final class LatestFixSubscriber implements Flow.Subscriber<VisualFix> {
        private final StreamId streamId;
        private final AtomicReference<VisualFix> latest = new AtomicReference<>();
        private final AtomicBoolean terminated = new AtomicBoolean(false);

        LatestFixSubscriber(StreamId streamId) {
            this.streamId = streamId;
        }

        VisualFix takeLatest() {
            return latest.getAndSet(null);
        }

        /**
         * Whether this session's fix stream has ended, by error or by completion — the signal {@link
         * #ensureSessionOpen} reads to tell a live session from a dead one. Set from the publisher's
         * own delivery thread, read from the runner's tick thread, hence the {@link AtomicBoolean}.
         * A graceful {@code onComplete} counts too: a live geolocation session is never expected to
         * complete on its own (the adapter treats an unrequested server-side completion as a failure),
         * so either terminal signal means the same thing here — no more fixes are coming.
         */
        boolean terminated() {
            return terminated.get();
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(VisualFix item) {
            latest.set(item); // CLAUDE.md rule 9: newest wins
        }

        @Override
        public void onError(Throwable throwable) {
            terminated.set(true);
            LOG.log(System.Logger.Level.WARNING,
                    () -> "visual-geo session errored for stream " + streamId.value()
                            + "; the next tick will reopen it once cv-service is reachable", throwable);
        }

        @Override
        public void onComplete() {
            terminated.set(true);
            LOG.log(System.Logger.Level.INFO, () -> "visual-geo session completed for stream " + streamId.value());
        }
    }
}
