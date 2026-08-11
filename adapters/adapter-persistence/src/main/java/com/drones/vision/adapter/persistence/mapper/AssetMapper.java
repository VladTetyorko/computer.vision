package com.drones.vision.adapter.persistence.mapper;

import com.drones.vision.adapter.persistence.entity.AssetEntity;
import com.drones.vision.warehouse.domain.model.Asset;
import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.CategoryId;
import com.drones.vision.kernel.DeviceId;
import com.drones.vision.kernel.GroupId;
import com.drones.vision.kernel.Ownership;
import com.drones.vision.kernel.UserId;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * {@link Asset} &harr; {@link AssetEntity} mapping, extracted from {@code JpaAssetRepository}
 * (docs/plans/active/LAYERING-REFACTOR-PLAN.md §3/§7 row C).
 */
public final class AssetMapper {

    private AssetMapper() {
    }

    public static AssetEntity toEntity(Asset asset) {
        Set<UUID> deviceIds = new LinkedHashSet<>();
        asset.devices().forEach(deviceId -> deviceIds.add(deviceId.value()));
        return new AssetEntity(asset.id().value(), asset.displayName(), asset.category().slug(),
                asset.ownership().ownerId().value(), asset.ownership().groupId().value(),
                deviceIds, asset.attributes(), asset.state());
    }

    public static Asset toDomain(AssetEntity entity) {
        Set<DeviceId> deviceIds = new LinkedHashSet<>();
        entity.deviceIds().forEach(value -> deviceIds.add(new DeviceId(value)));
        Ownership ownership = new Ownership(new UserId(entity.ownerId()), new GroupId(entity.groupId()));
        return new Asset(new AssetId(entity.id()), entity.displayName(), new CategoryId(entity.categoryId()),
                ownership, deviceIds, entity.attributes(), entity.state());
    }
}
