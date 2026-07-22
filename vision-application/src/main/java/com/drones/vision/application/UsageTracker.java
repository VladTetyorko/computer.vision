package com.drones.vision.application;

import com.drones.vision.domain.model.Asset;
import com.drones.vision.domain.model.AssetId;
import com.drones.vision.domain.model.AssetUsage;
import com.drones.vision.domain.model.Capability;
import com.drones.vision.domain.model.Device;
import com.drones.vision.domain.model.DeviceId;
import com.drones.vision.domain.model.GeoPosition;
import com.drones.vision.domain.model.Telemetry;
import com.drones.vision.domain.model.UsageId;
import com.drones.vision.domain.port.out.AssetRepositoryPort;
import com.drones.vision.domain.port.out.AssetUsageRepositoryPort;
import com.drones.vision.domain.port.out.DeviceRepositoryPort;
import com.drones.vision.domain.port.out.TelemetryRepositoryPort;
import com.drones.vision.domain.port.out.TelemetrySourcePort;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Flow;

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
 *   <li>{@link #onStreamStarted(DeviceId)} — resolves the device's owning
 *       asset; if this is the asset's <b>first</b> currently-active device,
 *       opens a new {@link AssetUsage} (persisted via {@link
 *       AssetUsageRepositoryPort}) and subscribes to a {@link
 *       TelemetrySourcePort} for each of the asset's {@link
 *       Capability#TELEMETRY}-capable devices that a registered source
 *       supports. An asset with several devices starting streams in
 *       succession still opens exactly <b>one</b> usage.</li>
 *   <li>Each telemetry sample received while a usage is open is persisted via
 *       {@link TelemetryRepositoryPort#save} and folds into the usage's cheap
 *       summary — {@code startPosition} (the first sample carrying a
 *       position), {@code lastPosition} (the most recent one), and {@code
 *       sampleCount} — via {@link #applySample}, kept in one method per the
 *       port's "single write path" note so it can be batched later.</li>
 *   <li>{@link #onStreamStopped(DeviceId)} — if this was the asset's
 *       <b>last</b> currently-active device, closes the open usage ({@link
 *       AssetUsage#closed(Instant)}) and unsubscribes/closes every telemetry
 *       subscription opened for it.</li>
 * </ul>
 *
 * <p>Plain class with no framework dependency; constructor-injected ports and
 * collaborators only, consistent with the rest of this module.
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

    private final ConcurrentHashMap<AssetId, Tracking> trackingByAsset = new ConcurrentHashMap<>();

    public UsageTracker(AssetRepositoryPort assetRepository, DeviceRepositoryPort deviceRepository,
                         AssetUsageRepositoryPort usageRepository, TelemetryRepositoryPort telemetryRepository,
                         List<TelemetrySourcePort> telemetrySources) {
        this.assetRepository = Objects.requireNonNull(assetRepository, "assetRepository must not be null");
        this.deviceRepository = Objects.requireNonNull(deviceRepository, "deviceRepository must not be null");
        this.usageRepository = Objects.requireNonNull(usageRepository, "usageRepository must not be null");
        this.telemetryRepository = Objects.requireNonNull(telemetryRepository, "telemetryRepository must not be null");
        Objects.requireNonNull(telemetrySources, "telemetrySources must not be null");
        this.telemetrySources = List.copyOf(telemetrySources);
    }

    /**
     * Notifies the tracker that a device's stream has started.
     *
     * @param deviceId the device whose stream started
     */
    public void onStreamStarted(DeviceId deviceId) {
        Objects.requireNonNull(deviceId, "deviceId must not be null");
        assetRepository.findByDeviceId(deviceId).ifPresent(this::deviceStreamStarted);
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

    private void deviceStreamStarted(Asset asset) {
        Tracking tracking = trackingByAsset.computeIfAbsent(asset.id(), id -> new Tracking());
        boolean openedNow;
        synchronized (tracking) {
            tracking.activeDevices++;
            openedNow = tracking.activeDevices == 1;
            if (openedNow) {
                AssetUsage usage = new AssetUsage(UsageId.random(), asset.id(), Instant.now(), null, null, null, 0);
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
                    source.open(device).subscribe(subscriber);
                    synchronized (tracking) {
                        tracking.telemetrySubscriptions.add(new TelemetrySubscription(source, deviceId, subscriber));
                    }
                    break; // first matching source wins, mirroring VideoSourceRegistry's selection rule
                }
            }
        }
    }

    private void unsubscribeTelemetry(Tracking tracking) {
        List<TelemetrySubscription> subscriptions;
        synchronized (tracking) {
            subscriptions = List.copyOf(tracking.telemetrySubscriptions);
            tracking.telemetrySubscriptions.clear();
        }
        for (TelemetrySubscription subscription : subscriptions) {
            subscription.subscriber().cancel();
            subscription.source().close(subscription.deviceId());
        }
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
        }
        telemetryRepository.save(usageId, sample);
        usageRepository.save(updated);
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
        private final List<TelemetrySubscription> telemetrySubscriptions = new ArrayList<>();
    }

    private record TelemetrySubscription(TelemetrySourcePort source, DeviceId deviceId,
                                          TelemetrySubscriber subscriber) {
    }

    /**
     * Requests one sample at a time (mirroring {@code StreamPipeline}'s
     * request(1)-at-a-time style) and forwards each to {@link
     * #applySample(AssetId, Telemetry)}. A source-side error simply stops
     * this subscription's flow of samples; it must never propagate into the
     * stream start/stop call that (indirectly) created it.
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
            // Best-effort telemetry: a source failure must not affect stream/usage lifecycle.
        }

        @Override
        public void onComplete() {
            // Source closed its publisher; nothing further to request.
        }

        void cancel() {
            Flow.Subscription s = subscription;
            if (s != null) {
                s.cancel();
            }
        }
    }
}
