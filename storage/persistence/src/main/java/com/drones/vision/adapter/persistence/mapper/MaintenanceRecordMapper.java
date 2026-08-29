package com.drones.vision.adapter.persistence.mapper;

import com.drones.vision.adapter.persistence.entity.MaintenanceRecordEntity;
import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.UserId;
import com.drones.vision.warehouse.domain.model.MaintenanceId;
import com.drones.vision.warehouse.domain.model.MaintenanceRecord;

/**
 * {@link MaintenanceRecord} &harr; {@link MaintenanceRecordEntity} mapping
 * (docs/plans/active/WAREHOUSE-UX-PLAN.md D7).
 */
public final class MaintenanceRecordMapper {

    private MaintenanceRecordMapper() {
    }

    public static MaintenanceRecordEntity toEntity(MaintenanceRecord record) {
        return new MaintenanceRecordEntity(record.id().value(), record.assetId().value(), record.kind(),
                record.openedAt(), record.closedAt(), record.openedBy().value(), record.summary(),
                record.flightSecondsAt());
    }

    public static MaintenanceRecord toDomain(MaintenanceRecordEntity entity) {
        return new MaintenanceRecord(new MaintenanceId(entity.id()), new AssetId(entity.assetId()), entity.kind(),
                entity.openedAt(), entity.closedAt(), new UserId(entity.openedBy()), entity.summary(),
                entity.flightSecondsAt());
    }
}
