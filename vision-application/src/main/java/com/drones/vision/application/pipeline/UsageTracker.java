package com.drones.vision.application.pipeline;

import com.drones.vision.domain.model.Asset;
import com.drones.vision.domain.model.AssetId;
import com.drones.vision.domain.model.AssetUsage;
import com.drones.vision.domain.model.Capability;
import com.drones.vision.domain.model.Device;
import com.drones.vision.domain.model.DeviceId;
import com.drones.vision.domain.model.GeoPosition;
import com.drones.vision.domain.model.StreamId;
import com.drones.vision.domain.model.Telemetry;
import com.drones.vision.domain.model.UsageId;
import com.drones.vision.domain.port.out.AssetRepositoryPort;
import com.drones.vision.domain.port.out.AssetUsageRepositoryPort;
import com.drones.vision.domain.port.out.DeviceRepositoryPort;
import com.drones.vision.domain.port.out.LiveUpdatePublisherPort;
import com.drones.vision.domain.port.out.TelemetryRepositoryPort;
import com.drones.vision.domain.port.out.TelemetrySourcePort;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.ScheduledExecutorService;
import com.drones.vision.application.geofence.GeofenceMonitor;
import com.drones.vision.application.stream.DefaultStreamService;
import com.drones.vision.application.stream.StreamService;

/**
 * Drives {@link AssetUsage} lifecycle and telemetry sampling from {@link
 * StreamService}'s stream start/stop notifications.
 *
 * <p>A device with no owning asset ({@link AssetRepositoryPort#findByDeviceId}
 * returns empty) is tracked as a no-op: not every registered device need be
 * wrapped by an asset yet, and this collaborator must never fail a stream
 * start/stop over that.
 *
 * <h2>Lifecycle</h2>
 * <ul>
 *   <li>{@link #onStreamStarted(DeviceId, StreamId)} — resolves the device's
 *       owning asset; if this is the asset's <b>first</b> currently-active
 *       device, opens a new {@link AssetUsage} (persisted via {@link
 *       AssetUsageRepositoryPort}), stamping it with the given {@code
 *       streamId} (docs/MVP2-PLAN.md §R, R-a2 — the live stream that opened
 *       it, later joined against {@link com.drones.vision.domain.model.DetectionResult}
 *       for replay), and subscribes to a {@link TelemetrySourcePort} for
 *       each of the asset's {@link Capability#TELEMETRY}-capable devices
 *       that a registered source supports. An asset with several devices
 *       starting streams in succession still opens exactly <b>one</b> usage,
 *       carrying the <b>first</b> device's {@code streamId} — later devices'
 *       stream ids are not recorded.</li>
 *   <li>Each telemetry sample received while a usage is open is persisted via
 *       {@link TelemetryRepositoryPort#save} and folds into the usage's cheap
 *       summary — {@code startPosition} (the first sample carrying a
 *       position), {@code lastPosition} (the most recent one), and {@code
 *       sampleCount} — via {@link #applySample}, kept in one method per the
 *       port's "single write path" note so it can be batched later. The same
 *       method also feeds the sample to {@link GeofenceMonitor#evaluate}
 *       (docs/OPS-CORE-PLAN.md §G) when one is configured, right alongside the
 *       existing persist/live-update steps.</li>
 *   <li>{@link #onStreamStopped(DeviceId)} — if this was the asset's
 *       <b>last</b> currently-active device, closes the open usage ({@link
 *       AssetUsage#closed(Instant)}) and unsubscribes/closes every telemetry
 *       subscription opened for it.</li>
 * </ul>
 *
 * <p>Plain class with no framework dependency; constructor-injected ports and
 * collaborators only, consistent with the rest of this module.
 *
 * <h2>Telemetry source supervision (docs/MVP2-PLAN.md &sect;S, S-a)</h2>
 * Exactly like {@code DefaultStreamService} does for the video source, {@link #subscribeTelemetry}
 * never subscribes a {@link TelemetrySubscriber} straight to a {@link TelemetrySourcePort#open}
 * result — it wraps it in a {@link SupervisedPublisher} so a telemetry source error/completion is
 * retried with the same capped exponential backoff instead of silently ending telemetry for the
 * rest of the usage. Unlike the video path, this does <b>not</b> publish a {@code PIPELINE_ERROR}
 * event on an outage — this class has no {@link com.drones.vision.domain.port.out.EventPublisherPort}
 * (and no {@code StreamId} to publish one against; telemetry is tracked per-asset/device, not
 * per-stream), and {@link TelemetrySubscriber#onError} was already, deliberately, a completely
 * silent no-op before this task (see the Gotchas below) — reconnection is new, the pre-existing
 * silence is not. {@link #unsubscribeTelemetry} calls {@link SupervisedPublisher#stop()}
 * synchronously (cheap, in-memory) so an explicit stream stop cancels any pending reopen
 * immediately, then releases the subscription/source off a background thread — see that method's
 * own javadoc.
 *
 * <h2>Threading</h2>
 * Per-asset state ({@link Tracking}) is reached through a {@link
 * ConcurrentHashMap} keyed by {@link AssetId}, and every read/mutate sequence
 * against one asset's tracking state is synchronized on that asset's {@code
 * Tracking} instance, so concurrent start/stop calls for different devices of
 * the same asset (or concurrent telemetry samples arriving on adapter
 * threads) never race the first-open/last-close decision or the usage
 * summary update.
 */
public final class UsageTracker {

    private final AssetRepositoryPort assetRepository;
    private final DeviceRepositoryPort deviceRepository;
    private final AssetUsageRepositoryPort usageRepository;
    private final TelemetryRepositoryPort telemetryRepository;
    private final List<TelemetrySourcePort> telemetrySources;
    private final LiveUpdatePublisherPort liveUpdatePublisherPort;
    private final GeofenceMonitor geofenceMonitor;
    private final long sourceInitialBackoffNanos;
    private final long sourceMaxBackoffNanos;

    /** One dedicated daemon thread scheduling every telemetry subscription's reopen retries; see {@code DefaultStreamService}'s own field of the same shape for why this is shared rather than per-subscription. */
    private final ScheduledExecutorService retryScheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "telemetry-supervisor");
        thread.setDaemon(true);
        return thread;
    });

    private final ConcurrentHashMap<AssetId, Tracking> trackingByAsset = new ConcurrentHashMap<>();

    public UsageTracker(AssetRepositoryPort assetRepository, DeviceRepositoryPort deviceRepository,
                         AssetUsageRepositoryPort usageRepository, TelemetryRepositoryPort telemetryRepository,
                         List<TelemetrySourcePort> telemetrySources) {
        this(assetRepository, deviceRepository, usageRepository, telemetryRepository, telemetrySources, null);
    }

    /**
     * Same as the 5-argument constructor, plus a {@link LiveUpdatePublisherPort} collaborator
     * (docs/REALTIME-PLAN.md §4): every telemetry sample folded via {@link #applySample} is
     * announced through it.
     *
     * @param liveUpdatePublisherPort nullable, following the same convention as {@code
     *                                 DefaultStreamService}'s own collaborator of the same type:
     *                                 {@code null} means no live-update announcements.
     */
    public UsageTracker(AssetRepositoryPort assetRepository, DeviceRepositoryPort deviceRepository,
                         AssetUsageRepositoryPort usageRepository, TelemetryRepositoryPort telemetryRepository,
                         List<TelemetrySourcePort> telemetrySources, LiveUpdatePublisherPort liveUpdatePublisherPort) {
        this(assetRepository, deviceRepository, usageRepository, telemetryRepository, telemetrySources,
                liveUpdatePublisherPort, null);
    }

    /**
     * Same as the 6-argument constructor, plus a {@link GeofenceMonitor} collaborator
     * (docs/OPS-CORE-PLAN.md §G): every telemetry sample folded via {@link #applySample} is also
     * evaluated for geofence breaches, right alongside the existing persist/live-update steps.
     *
     * @param geofenceMonitor nullable, following the same convention as {@code
     *                        liveUpdatePublisherPort}: {@code null} means no geofence evaluation at
     *                        all for samples this tracker applies.
     */
    public UsageTracker(AssetRepositoryPort assetRepository, DeviceRepositoryPort deviceRepository,
                         AssetUsageRepositoryPort usageRepository, TelemetryRepositoryPort telemetryRepository,
                         List<TelemetrySourcePort> telemetrySources, LiveUpdatePublisherPort liveUpdatePublisherPort,
                         GeofenceMonitor geofenceMonitor) {
        this(assetRepository, deviceRepository, usageRepository, telemetryRepository, telemetrySources,
                liveUpdatePublisherPort, geofenceMonitor, SupervisedPublisher.INITIAL_BACKOFF_NANOS,
                SupervisedPublisher.MAX_BACKOFF_NANOS);
    }

    /**
     * Test seam: same as the 7-argument constructor, with explicit (typically much smaller)
     * telemetry-source reopen backoff bounds so supervision-related tests don't have to wait out a
     * real 1s-30s backoff. Production always uses the 7-argument constructor's defaults.
     */
    UsageTracker(AssetRepositoryPort assetRepository, DeviceRepositoryPort deviceRepository,
                 AssetUsageRepositoryPort usageRepository, TelemetryRepositoryPort telemetryRepository,
                 List<TelemetrySourcePort> telemetrySources, LiveUpdatePublisherPort liveUpdatePublisherPort,
                 GeofenceMonitor geofenceMonitor, long sourceInitialBackoffNanos, long sourceMaxBackoffNanos) {
        this.assetRepository = Objects.requireNonNull(assetRepository, "assetRepository must not be null");
        this.deviceRepository = Objects.requireNonNull(deviceRepository, "deviceRepository must not be null");
        this.usageRepository = Objects.requireNonNull(usageRepository, "usageRepository must not be null");
        this.telemetryRepository = Objects.requireNonNull(telemetryRepository, "telemetryRepository must not be null");
        Objects.requireNonNull(telemetrySources, "telemetrySources must not be null");
        this.telemetrySources = List.copyOf(telemetrySources);
        this.liveUpdatePublisherPort = liveUpdatePublisherPort; // nullable: no live-update announcements when absent
        this.geofenceMonitor = geofenceMonitor; // nullable: no geofence evaluation when absent
        this.sourceInitialBackoffNanos = sourceInitialBackoffNanos;
        this.sourceMaxBackoffNanos = sourceMaxBackoffNanos;
    }

    /**
     * Notifies the tracker that a device's stream has started.
     *
     * @param deviceId the device whose stream started
     * @param streamId the id of the stream that started; recorded onto the {@link AssetUsage}
     *                 only when this call is the one that opens it (the asset's first
     *                 currently-active device) — see the class javadoc
     */
    public void onStreamStarted(DeviceId deviceId, StreamId streamId) {
        Objects.requireNonNull(deviceId, "deviceId must not be null");
        Objects.requireNonNull(streamId, "streamId must not be null");
        assetRepository.findByDeviceId(deviceId).ifPresent(asset -> deviceStreamStarted(asset, streamId));
    }

    /**
     * Notifies the tracker that a device's stream has stopped.
     *
     * @param deviceId the device whose stream stopped
     */
    public void onStreamStopped(DeviceId deviceId) {
        Objects.requireNonNull(deviceId, "deviceId must not be null");
        assetRepository.findByDeviceId(deviceId).ifPresent(this::deviceStreamStopped);
    }

    /**
     * Resolves the asset that owns {@code deviceId}, if any — best-effort, used by {@code
     * DetectionEventEngine} (docs/MVP2-PLAN.md §E, E-a) to stamp a {@code DetectionEvent}'s {@code
     * assetId} at open time.
     *
     * @param deviceId the device to resolve
     * @return the owning asset's id, or {@link Optional#empty()} if the device has no owning asset
     */
    public Optional<AssetId> resolveAsset(DeviceId deviceId) {
        Objects.requireNonNull(deviceId, "deviceId must not be null");
        return assetRepository.findByDeviceId(deviceId).map(Asset::id);
    }

    /**
     * Best-effort freshest known position for {@code assetId}'s currently open usage
     * (docs/MVP2-PLAN.md §E, E-a) — the same {@code lastPosition} an {@link AssetUsage}
     * accumulates as telemetry samples arrive (see {@link #applySample}), read back rather than
     * queried fresh from {@link TelemetryRepositoryPort} directly, which has no "give me the
     * latest sample" shape to ask for one. Empty when the asset has no currently open usage, or
     * the open usage has not yet received a positioned telemetry sample.
     *
     * @param assetId the asset to inspect
     * @return the freshest position, or {@link Optional#empty()} if unavailable
     */
    public Optional<GeoPosition> latestPosition(AssetId assetId) {
        Objects.requireNonNull(assetId, "assetId must not be null");
        Tracking tracking = trackingByAsset.get(assetId);
        if (tracking == null) {
            return Optional.empty();
        }
        synchronized (tracking) {
            return tracking.usage == null ? Optional.empty() : Optional.ofNullable(tracking.usage.lastPosition());
        }
    }

    /**
     * The most recent telemetry sample ever received for {@code assetId} (docs/MVP3-PLAN.md C-a) —
     * used to derive a fleet-summary attention row's battery percent and telemetry staleness.
     *
     * <p>Deliberately <b>not</b> scoped to the currently open usage the way {@link
     * #latestPosition(AssetId)} is: that method answers "where is it right now", which is honestly
     * unknowable once streaming stops, but staleness ("how long since we last heard from this
     * asset") is most useful exactly once an asset has gone quiet — so this method keeps returning
     * the last sample it ever saw for the asset rather than going empty the moment its usage closes.
     * The underlying {@link Tracking} instance is never evicted from {@link #trackingByAsset} once
     * created, so the sample survives across the asset's whole tracked lifetime, not just one usage.
     *
     * @param assetId the asset to inspect
     * @return the freshest sample, or {@link Optional#empty()} if the asset has never reported
     *         telemetry (including one that has never streamed at all)
     */
    public Optional<Telemetry> latestTelemetry(AssetId assetId) {
        Objects.requireNonNull(assetId, "assetId must not be null");
        Tracking tracking = trackingByAsset.get(assetId);
        if (tracking == null) {
            return Optional.empty();
        }
        synchronized (tracking) {
            return Optional.ofNullable(tracking.lastSample);
        }
    }

    private void deviceStreamStarted(Asset asset, StreamId streamId) {
        Tracking tracking = trackingByAsset.computeIfAbsent(asset.id(), id -> new Tracking());
        boolean openedNow;
        synchronized (tracking) {
            tracking.activeDevices++;
            openedNow = tracking.activeDevices == 1;
            if (openedNow) {
                AssetUsage usage =
                        new AssetUsage(UsageId.random(), asset.id(), Instant.now(), null, null, null, 0, streamId);
                tracking.usage = usageRepository.save(usage);
            }
        }
        if (openedNow) {
            subscribeTelemetry(asset, tracking);
        }
    }

    private void deviceStreamStopped(Asset asset) {
        Tracking tracking = trackingByAsset.get(asset.id());
        if (tracking == null) {
            return;
        }
        AssetUsage closedUsage = null;
        synchronized (tracking) {
            if (tracking.activeDevices > 0) {
                tracking.activeDevices--;
            }
            if (tracking.activeDevices == 0 && tracking.usage != null) {
                closedUsage = tracking.usage.closed(Instant.now());
                tracking.usage = null;
            }
        }
        if (closedUsage != null) {
            unsubscribeTelemetry(tracking);
            usageRepository.save(closedUsage);
        }
    }

    private void subscribeTelemetry(Asset asset, Tracking tracking) {
        for (DeviceId deviceId : asset.devices()) {
            Optional<Device> deviceOpt = deviceRepository.findById(deviceId);
            if (deviceOpt.isEmpty()) {
                continue;
            }
            Device device = deviceOpt.get();
            if (!device.capabilities().contains(Capability.TELEMETRY)) {
                continue;
            }
            for (TelemetrySourcePort source : telemetrySources) {
                if (source.supports(device)) {
                    TelemetrySubscriber subscriber = new TelemetrySubscriber(asset.id());
                    // docs/MVP2-PLAN.md §S, S-a: supervised exactly like the video source (see this
                    // class's own javadoc for why no PIPELINE_ERROR is published here).
                    SupervisedPublisher<Telemetry> supervised = new SupervisedPublisher<>(() -> source.open(device),
                            cause -> { }, retryScheduler, sourceInitialBackoffNanos, sourceMaxBackoffNanos);
                    supervised.subscribe(subscriber);
                    synchronized (tracking) {
                        tracking.telemetrySubscriptions.add(
                                new TelemetrySubscription(source, deviceId, subscriber, supervised));
                    }
                    break; // first matching source wins, mirroring VideoSourceRegistry's selection rule
                }
            }
        }
    }

    /**
     * Unsubscribes and releases every telemetry subscription opened for a now-closed usage.
     * {@link SupervisedPublisher#stop()} runs synchronously here — cheap, in-memory, and it must
     * happen before this method returns so a pending scheduled reopen can never race a legitimate
     * close (docs/MVP2-PLAN.md §S, S-a, same reasoning as {@code DefaultStreamService#stop}). The
     * actual {@link Flow.Subscription#cancel()}/{@link TelemetrySourcePort#close} calls run on a
     * background thread instead, for the same reason {@code DefaultStreamService} defers its own
     * source teardown: an adapter's {@code close()} is not guaranteed to be fast, and this method is
     * itself called synchronously from {@code DefaultStreamService#stop}, which must return promptly.
     */
    private void unsubscribeTelemetry(Tracking tracking) {
        List<TelemetrySubscription> subscriptions;
        synchronized (tracking) {
            subscriptions = List.copyOf(tracking.telemetrySubscriptions);
            tracking.telemetrySubscriptions.clear();
        }
        for (TelemetrySubscription subscription : subscriptions) {
            subscription.supervisedPublisher().stop();
        }
        if (subscriptions.isEmpty()) {
            return;
        }
        Thread.ofVirtual().name("telemetry-teardown").start(() -> {
            for (TelemetrySubscription subscription : subscriptions) {
                subscription.subscriber().cancel();
                subscription.source().close(subscription.deviceId());
            }
        });
    }

    /**
     * Persists one telemetry sample and folds it into its usage's summary —
     * the single write path for both, per this port's javadoc note about
     * later batching.
     */
    private void applySample(AssetId assetId, Telemetry sample) {
        Tracking tracking = trackingByAsset.get(assetId);
        if (tracking == null) {
            return;
        }
        UsageId usageId;
        AssetUsage updated;
        synchronized (tracking) {
            if (tracking.usage == null) {
                return; // usage already closed: drop a straggling sample rather than reopen it
            }
            usageId = tracking.usage.id();
            GeoPosition position = toPosition(sample);
            GeoPosition startPosition = tracking.usage.startPosition();
            GeoPosition lastPosition = tracking.usage.lastPosition();
            if (position != null) {
                if (startPosition == null) {
                    startPosition = position;
                }
                lastPosition = position;
            }
            updated = tracking.usage.withPositions(startPosition, lastPosition)
                    .withSampleCount(tracking.usage.sampleCount() + 1);
            tracking.usage = updated;
            tracking.lastSample = sample; // docs/MVP3-PLAN.md C-a: outlives the usage, see latestTelemetry's javadoc
        }
        telemetryRepository.save(usageId, sample);
        usageRepository.save(updated);
        if (liveUpdatePublisherPort != null) { // docs/REALTIME-PLAN.md §4
            liveUpdatePublisherPort.publishTelemetryAppended(assetId, sample);
        }
        if (geofenceMonitor != null) { // docs/OPS-CORE-PLAN.md §G
            geofenceMonitor.evaluate(assetId, sample);
        }
    }

    private static GeoPosition toPosition(Telemetry sample) {
        if (sample.latitude() == null || sample.longitude() == null) {
            return null;
        }
        return new GeoPosition(sample.latitude(), sample.longitude(), sample.altitudeMeters());
    }

    /** Per-asset mutable tracking state; every access is synchronized on the instance itself. */
    private static final class Tracking {
        private int activeDevices;
        private AssetUsage usage;
        /** docs/MVP3-PLAN.md C-a: the freshest sample ever seen, kept even once {@link #usage} closes — see {@link #latestTelemetry(AssetId)}. */
        private Telemetry lastSample;
        private final List<TelemetrySubscription> telemetrySubscriptions = new ArrayList<>();
    }

    private record TelemetrySubscription(TelemetrySourcePort source, DeviceId deviceId,
                                          TelemetrySubscriber subscriber, SupervisedPublisher<Telemetry> supervisedPublisher) {
    }

    /**
     * Requests one sample at a time (mirroring {@code StreamPipeline}'s
     * request(1)-at-a-time style) and forwards each to {@link
     * #applySample(AssetId, Telemetry)}. A source-side error simply stops
     * this subscription's flow of samples; it must never propagate into the
     * stream start/stop call that (indirectly) created it.
     *
     * <p>{@code onSubscribe} is called again, updating {@link #subscription}, every time {@link
     * SupervisedPublisher} (docs/MVP2-PLAN.md §S, S-a) reopens the source after an outage — this
     * class needs no reconnect logic of its own, it just keeps receiving the normal {@code
     * onSubscribe}/{@code onNext} traffic the wrapper forwards.
     */
    private final class TelemetrySubscriber implements Flow.Subscriber<Telemetry> {
        private final AssetId assetId;
        private volatile Flow.Subscription subscription;

        TelemetrySubscriber(AssetId assetId) {
            this.assetId = assetId;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(1);
        }

        @Override
        public void onNext(Telemetry item) {
            applySample(assetId, item);
            Flow.Subscription s = subscription;
            if (s != null) {
                s.request(1);
            }
        }

        @Override
        public void onError(Throwable throwable) {
            // docs/MVP2-PLAN.md §S, S-a: unreachable in production -- subscribeTelemetry always
            // wraps the source in a SupervisedPublisher, which intercepts onError/onComplete
            // itself (to retry) and never forwards either one downstream. Left as a harmless no-op
            // (not e.g. an AssertionError) since this class still implements the public
            // Flow.Subscriber contract directly, and best-effort telemetry must never affect
            // stream/usage lifecycle even if something one day subscribes this unsupervised.
        }

        @Override
        public void onComplete() {
            // See onError above: unreachable in production, kept as a harmless no-op.
        }

        void cancel() {
            Flow.Subscription s = subscription;
            if (s != null) {
                s.cancel();
            }
        }
    }
}
