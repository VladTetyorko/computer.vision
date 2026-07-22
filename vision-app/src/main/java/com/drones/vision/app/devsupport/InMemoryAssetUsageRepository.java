package com.drones.vision.app.devsupport;

import com.drones.vision.domain.model.AssetId;
import com.drones.vision.domain.model.AssetUsage;
import com.drones.vision.domain.model.UsageId;
import com.drones.vision.domain.port.out.AssetUsageRepositoryPort;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory {@link AssetUsageRepositoryPort}: dev/Phase-0 fallback with no
 * durability across restarts and linear-scan queries.
 *
 * <p>Replaced by {@code adapter-persistence} (JPA/Postgres, TimescaleDB-ready
 * per the port's javadoc), planned for Phase 2.
 */
public final class InMemoryAssetUsageRepository implements AssetUsageRepositoryPort {

    private final Map<UsageId, AssetUsage> usages = new ConcurrentHashMap<>();

    @Override
    public AssetUsage save(AssetUsage usage) {
        usages.put(usage.id(), usage);
        return usage;
    }

    @Override
    public Optional<AssetUsage> findById(UsageId id) {
        return Optional.ofNullable(usages.get(id));
    }

    @Override
    public List<AssetUsage> findRecentByAsset(AssetId assetId, int limit) {
        return usages.values().stream()
                .filter(usage -> usage.assetId().equals(assetId))
                .sorted(Comparator.comparing(AssetUsage::startedAt).reversed())
                .limit(limit)
                .toList();
    }

    @Override
    public Optional<AssetUsage> findOpenByAsset(AssetId assetId) {
        return usages.values().stream()
                .filter(usage -> usage.assetId().equals(assetId) && usage.endedAt() == null)
                .findFirst();
    }
}
