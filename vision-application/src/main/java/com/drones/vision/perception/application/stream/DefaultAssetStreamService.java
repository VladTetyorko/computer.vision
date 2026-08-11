package com.drones.vision.perception.application.stream;

import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.Capability;
import com.drones.vision.kernel.DeviceId;
import com.drones.vision.kernel.StreamId;
import com.drones.vision.perception.domain.model.PipelineConfig;
import com.drones.vision.warehouse.application.device.DeviceService;
import com.drones.vision.warehouse.domain.model.Asset;
import com.drones.vision.warehouse.domain.port.AssetRepositoryPort;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;

/**
 * The one implementation of {@link AssetStreamService}.
 *
 * <p>Resolves the asset directly against {@link AssetRepositoryPort} rather than through {@code
 * AssetService} — {@code perception -> warehouse} is the legal direction
 * (docs/plans/active/DOMAIN-SEPARATION-W1.md &sect;15, W1.6e), and reaching the repository port
 * mirrors how {@code UsageTracker} already resolves assets/devices from this same context. Devices
 * are still reached through {@link DeviceService}, not {@code DeviceRepositoryPort}, matching
 * warehouse's own convention (moved here unchanged from {@code DefaultAssetService}'s prior
 * javadoc): a plain lookup carries no rule to duplicate either way, so there was no reason to
 * diverge from the existing idiom.
 *
 * <h2>Threading</h2>
 * Holds no mutable state — all shared state is reached through the injected collaborators.
 */
public final class DefaultAssetStreamService implements AssetStreamService {

    private final AssetRepositoryPort assetRepository;
    private final DeviceService deviceService;
    private final StreamService streamService;

    public DefaultAssetStreamService(AssetRepositoryPort assetRepository, DeviceService deviceService,
                                      StreamService streamService) {
        this.assetRepository = Objects.requireNonNull(assetRepository, "assetRepository must not be null");
        this.deviceService = Objects.requireNonNull(deviceService, "deviceService must not be null");
        this.streamService = Objects.requireNonNull(streamService, "streamService must not be null");
    }

    @Override
    public StreamId startStream(AssetId id, DeviceId device, PipelineConfig config) {
        return startStream(id, device, config, TrackingConfigPatch.NOTHING);
    }

    @Override
    public StreamId startStream(AssetId id, DeviceId device, PipelineConfig config, TrackingConfigPatch tracking) {
        Objects.requireNonNull(config, "config must not be null");
        Objects.requireNonNull(tracking, "tracking must not be null");
        Asset asset = require(id);
        if (!asset.isActive()) {
            throw new IllegalStateException("Asset is not in service: " + asset.displayName());
        }
        if (device != null && !asset.devices().contains(device)) {
            throw new IllegalArgumentException(
                    "Device " + device.value() + " does not belong to asset " + id.value());
        }
        return streamService.start(device != null ? device : resolveSingleVideoDevice(asset), config, tracking);
    }

    /**
     * Picks the asset's single active video-capable device.
     *
     * <p>Deactivated and deleted devices are invisible here: a drone with one retired camera and
     * one working one should just start the working one, not report ambiguity.
     */
    private DeviceId resolveSingleVideoDevice(Asset asset) {
        List<DeviceId> videoCapable = asset.devices().stream()
                .filter(deviceId -> deviceService.find(deviceId)
                        .map(d -> d.isActive() && d.capabilities().contains(Capability.VIDEO))
                        .orElse(false))
                .toList();
        if (videoCapable.isEmpty()) {
            throw new IllegalArgumentException("Asset " + asset.id().value()
                    + " has no active video-capable device; specify one explicitly");
        }
        if (videoCapable.size() > 1) {
            throw new IllegalArgumentException("Asset " + asset.id().value()
                    + " has multiple video-capable devices, specify which one to start: " + videoCapable);
        }
        return videoCapable.get(0);
    }

    private Asset require(AssetId id) {
        Objects.requireNonNull(id, "id must not be null");
        return assetRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Unknown asset: " + id.value()));
    }
}
