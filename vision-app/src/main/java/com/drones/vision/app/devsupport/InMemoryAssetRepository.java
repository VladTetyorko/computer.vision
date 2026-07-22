package com.drones.vision.app.devsupport;

import com.drones.vision.domain.model.Asset;
import com.drones.vision.domain.model.AssetId;
import com.drones.vision.domain.model.DeviceId;
import com.drones.vision.domain.port.out.AssetRepositoryPort;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory {@link AssetRepositoryPort}: dev/Phase-0 fallback with no
 * durability across restarts and a linear-scan {@link #findByDeviceId}.
 *
 * <p>Replaced by {@code adapter-persistence} (JPA/Postgres), planned for
 * Phase 2.
 */
public final class InMemoryAssetRepository implements AssetRepositoryPort {

    private final Map<AssetId, Asset> assets = new ConcurrentHashMap<>();

    @Override
    public Asset save(Asset asset) {
        assets.put(asset.id(), asset);
        return asset;
    }

    @Override
    public Optional<Asset> findById(AssetId id) {
        return Optional.ofNullable(assets.get(id));
    }

    @Override
    public List<Asset> findAll() {
        return List.copyOf(assets.values());
    }

    @Override
    public Optional<Asset> findByDeviceId(DeviceId deviceId) {
        return assets.values().stream().filter(asset -> asset.devices().contains(deviceId)).findFirst();
    }

    @Override
    public void deleteById(AssetId id) {
        assets.remove(id);
    }
}
