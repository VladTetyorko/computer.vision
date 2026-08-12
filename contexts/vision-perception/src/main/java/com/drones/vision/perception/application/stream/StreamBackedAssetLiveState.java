package com.drones.vision.perception.application.stream;

import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.DeviceId;
import com.drones.vision.kernel.StreamId;
import com.drones.vision.kernel.Telemetry;
import com.drones.vision.perception.application.pipeline.UsageTracker;
import com.drones.vision.perception.domain.model.DetectionEvent;
import com.drones.vision.perception.domain.model.DetectionEventState;
import com.drones.vision.perception.domain.port.DetectionEventRepositoryPort;
import com.drones.vision.warehouse.domain.port.AssetLiveStatePort;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The one implementation of {@code AssetLiveStatePort} — perception's answer to warehouse's
 * declared need to read live state (docs/plans/active/DOMAIN-SEPARATION-W1.md &sect;15, W1.6e).
 *
 * <p>This is the inversion made concrete: warehouse owns the port's shape, in its own kernel-typed
 * vocabulary; this class, filed in perception (which holds the running streams, the telemetry
 * tracker and the detection-event store), is the one thing that may compile-depend on all three at
 * once to answer it. Warehouse never depends on {@link StreamService}, {@link UsageTracker} or
 * {@link DetectionEventRepositoryPort} directly again — {@code perception -> warehouse} is already
 * the legal direction, so implementing warehouse's port here needs no third module and {@code
 * vision-app} only has to wire it.
 *
 * <h2>Threading</h2>
 * Holds no mutable state — every read is answered fresh from the injected collaborators, which are
 * themselves safe for concurrent use.
 */
public final class StreamBackedAssetLiveState implements AssetLiveStatePort {

    private final StreamService streamService;
    private final UsageTracker usageTracker;
    private final DetectionEventRepositoryPort detectionEventRepositoryPort;

    public StreamBackedAssetLiveState(StreamService streamService, UsageTracker usageTracker,
                                       DetectionEventRepositoryPort detectionEventRepositoryPort) {
        this.streamService = Objects.requireNonNull(streamService, "streamService must not be null");
        this.usageTracker = Objects.requireNonNull(usageTracker, "usageTracker must not be null");
        this.detectionEventRepositoryPort = Objects.requireNonNull(detectionEventRepositoryPort,
                "detectionEventRepositoryPort must not be null");
    }

    @Override
    public Map<DeviceId, StreamId> activeStreamsByDevice() {
        Map<DeviceId, StreamId> byDevice = new HashMap<>();
        for (ActiveStream active : streamService.streams()) {
            byDevice.put(active.deviceId(), active.streamId());
        }
        return byDevice;
    }

    @Override
    public int stopStreamsForDevices(Collection<DeviceId> deviceIds) {
        Objects.requireNonNull(deviceIds, "deviceIds must not be null");
        int stopped = 0;
        for (ActiveStream active : streamService.streams()) {
            if (deviceIds.contains(active.deviceId())) {
                streamService.stop(active.streamId());
                stopped++;
            }
        }
        return stopped;
    }

    @Override
    public Optional<Telemetry> latestTelemetry(AssetId assetId) {
        return usageTracker.latestTelemetry(assetId);
    }

    @Override
    public Map<AssetId, Integer> openDetectionEventCounts(int scanLimit) {
        Map<AssetId, Integer> counts = new HashMap<>();
        for (DetectionEvent event : detectionEventRepositoryPort.findRecent(null, scanLimit)) {
            if (event.state() == DetectionEventState.OPEN && event.assetId() != null) {
                counts.merge(event.assetId(), 1, Integer::sum);
            }
        }
        return counts;
    }
}
