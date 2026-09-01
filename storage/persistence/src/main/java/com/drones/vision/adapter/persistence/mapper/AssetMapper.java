package com.drones.vision.adapter.persistence.mapper;

import com.drones.vision.adapter.persistence.entity.AssetEntity;
import com.drones.vision.warehouse.domain.model.Asset;
import com.drones.vision.warehouse.domain.model.Custody;
import com.drones.vision.warehouse.domain.model.Identity;
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
 *
 * <p>{@link Identity} and {@link Custody} are flattened onto individual {@code AssetEntity}
 * columns (docs/plans/active/WAREHOUSE-UX-PLAN.md D1/D2) rather than stored as jsonb — both are
 * small, fixed-shape, 1:1 with an asset, and {@code custodian_id}/{@code location} need to be
 * queryable columns in their own right for a future "who has what" report, the same reasoning
 * {@code Ownership} already got when this entity was first designed.
 */
public final class AssetMapper {

    private AssetMapper() {
    }

    public static AssetEntity toEntity(Asset asset) {
        Set<UUID> deviceIds = new LinkedHashSet<>();
        asset.devices().forEach(deviceId -> deviceIds.add(deviceId.value()));
        Identity identity = asset.identity();
        Custody custody = asset.custody();
        UUID custodianId = custody.custodianId() == null ? null : custody.custodianId().value();
        return new AssetEntity(asset.id().value(), asset.displayName(), asset.category().slug(),
                asset.ownership().ownerId().value(), asset.ownership().groupId().value(),
                deviceIds, asset.attributes(), asset.state(), identity.serialNumber(), identity.make(),
                identity.model(), identity.registration(), custodianId, custody.location(), custody.since(),
                asset.inventoryState(), asset.createdAt(), asset.updatedAt());
    }

    public static Asset toDomain(AssetEntity entity) {
        Set<DeviceId> deviceIds = new LinkedHashSet<>();
        entity.deviceIds().forEach(value -> deviceIds.add(new DeviceId(value)));
        Ownership ownership = new Ownership(new UserId(entity.ownerId()), new GroupId(entity.groupId()));
        Identity identity = new Identity(entity.serialNumber(), entity.make(), entity.model(),
                entity.registration());
        UserId custodianId = entity.custodianId() == null ? null : new UserId(entity.custodianId());
        Custody custody = new Custody(custodianId, entity.location(), entity.custodySince());
        return new Asset(new AssetId(entity.id()), entity.displayName(), new CategoryId(entity.categoryId()),
                ownership, deviceIds, entity.attributes(), entity.state(), identity, custody,
                entity.inventoryState(), entity.createdAt(), entity.updatedAt());
    }
}
