package com.drones.vision.app.devsupport;

import com.drones.vision.domain.model.AssetId;
import com.drones.vision.domain.model.AssetImage;
import com.drones.vision.domain.port.out.AssetImageRepositoryPort;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory {@link AssetImageRepositoryPort}: dev fallback with no durability across restarts
 * (docs/UX-REWORK-PLAN.md §U-d item 3).
 *
 * <p>Replaced by {@code adapter-persistence}'s {@code JpaAssetImageRepository} behind {@code
 * vision.persistence.enabled}.
 */
public final class InMemoryAssetImageRepository implements AssetImageRepositoryPort {

    private final Map<AssetId, AssetImage> images = new ConcurrentHashMap<>();

    @Override
    public void save(AssetId assetId, AssetImage image) {
        images.put(assetId, image);
    }

    @Override
    public Optional<AssetImage> findByAssetId(AssetId assetId) {
        return Optional.ofNullable(images.get(assetId));
    }

    @Override
    public boolean existsByAssetId(AssetId assetId) {
        return images.containsKey(assetId);
    }

    @Override
    public void deleteByAssetId(AssetId assetId) {
        images.remove(assetId);
    }
}
