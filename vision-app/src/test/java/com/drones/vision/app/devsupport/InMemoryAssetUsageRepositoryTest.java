package com.drones.vision.app.devsupport;

import com.drones.vision.kernel.AssetId;
import com.drones.vision.warehouse.domain.model.AssetUsage;
import com.drones.vision.kernel.UsageId;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class InMemoryAssetUsageRepositoryTest {

    private static AssetUsage usage(UsageId id, AssetId assetId, Instant startedAt) {
        return new AssetUsage(id, assetId, startedAt, startedAt.plusSeconds(1), null, null, 0);
    }

    @Test
    void findRecentSpansEveryAssetNewestFirstBoundedByLimit() {
        InMemoryAssetUsageRepository repository = new InMemoryAssetUsageRepository();
        AssetId assetA = AssetId.random();
        AssetId assetB = AssetId.random();
        Instant base = Instant.parse("2026-08-04T10:00:00Z");
        AssetUsage oldest = usage(UsageId.random(), assetA, base);
        AssetUsage middle = usage(UsageId.random(), assetB, base.plusSeconds(10));
        AssetUsage newest = usage(UsageId.random(), assetA, base.plusSeconds(20));
        repository.save(oldest);
        repository.save(newest);
        repository.save(middle);

        List<AssetUsage> recent = repository.findRecent(2);

        assertEquals(List.of(newest.id(), middle.id()), recent.stream().map(AssetUsage::id).toList(),
                "findRecent must span every asset, not just one");
    }

    @Test
    void findRecentReturnsEmptyListWhenNothingIsStored() {
        InMemoryAssetUsageRepository repository = new InMemoryAssetUsageRepository();

        assertEquals(List.of(), repository.findRecent(10));
    }
}
