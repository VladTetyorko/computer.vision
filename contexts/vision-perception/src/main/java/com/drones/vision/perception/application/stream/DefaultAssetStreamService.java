package com.drones.vision.perception.application.stream;

import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.Capability;
import com.drones.vision.kernel.DeviceId;
import com.drones.vision.kernel.StreamId;
import com.drones.vision.perception.domain.model.PipelineConfig;
import com.drones.vision.warehouse.application.device.DeviceService;
import com.drones.vision.warehouse.application.directory.AssetDirectoryService;
import com.drones.vision.warehouse.domain.model.Asset;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;

/**
 * The one implementation of {@link AssetStreamService}.
 *
 * <p>Resolves the asset through warehouse's {@link AssetDirectoryService} rather than {@code
 * AssetService} or its raw {@code AssetRepositoryPort} directly
 * (docs/plans/active/ARCHITECTURE-AUDIT-2026-08-26.md R5) — see that type's own javadoc for why a
 * caller that must not cycle back through {@code AssetService}'s {@code AssetLiveStatePort}
 * dependency reaches for this narrower seam instead; {@code perception -> warehouse} is the legal
 * direction (docs/plans/active/DOMAIN-SEPARATION-W1.md &sect;15, W1.6e). Devices are still reached
 * through {@link DeviceService}, not the directory service's device lookup, matching warehouse's
 * own convention (moved here unchanged from {@code DefaultAssetService}'s prior javadoc): a plain
 * lookup carries no rule to duplicate either way, so there was no reason to diverge from the
 * existing idiom.
 *
 * <h2>Threading</h2>
 * Holds no mutable state — all shared state is reached through the injected collaborators.
 */
public final class DefaultAssetStreamService implements AssetStreamService {

    private final AssetDirectoryService assetDirectory;
    private final DeviceService deviceService;
    private final StreamService streamService;

    public DefaultAssetStreamService(AssetDirectoryService assetDirectory, DeviceService deviceService,
                                      StreamService streamService) {
        this.assetDirectory = Objects.requireNonNull(assetDirectory, "assetDirectory must not be null");
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
        return assetDirectory.find(id)
                .orElseThrow(() -> new NoSuchElementException("Unknown asset: " + id.value()));
    }
}
