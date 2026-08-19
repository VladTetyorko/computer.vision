package com.drones.vision.adapter.persistence.mapper;

import com.drones.vision.adapter.persistence.entity.CameraPoseEntity;
import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.GeoPosition;
import com.drones.vision.kernel.UserId;
import com.drones.vision.map.domain.model.CameraPose;
import com.drones.vision.map.domain.model.LayerId;

/**
 * {@link CameraPose} ⟷ {@link CameraPoseEntity} (docs/plans/active/FIXED-CAMERA-GEO-PLAN.md decision D4).
 *
 * <p>{@code position} is flattened rather than nested, matching the columns {@code
 * V22__fixed_camera_geo.sql} defines — same "flatten a small value type" choice {@code MarkMapper}
 * makes for {@link com.drones.vision.map.domain.model.Mark#position()}.
 */
public final class CameraPoseMapper {

    private CameraPoseMapper() {
    }

    public static CameraPoseEntity toEntity(CameraPose pose) {
        GeoPosition position = pose.position();
        return new CameraPoseEntity(pose.assetId().value(), position.latitude(), position.longitude(),
                position.altitudeMeters(), pose.aglMeters(), pose.yawDegrees(), pose.pitchDegrees(),
                pose.hfovDegrees(), pose.targetLayerId() == null ? null : pose.targetLayerId().value(),
                pose.source(), pose.rmsErrorPixels(), pose.updatedAt(), pose.updatedBy().value());
    }

    public static CameraPose toDomain(CameraPoseEntity entity) {
        GeoPosition position = new GeoPosition(entity.latitude(), entity.longitude(), entity.altitudeMeters());
        LayerId targetLayerId = entity.targetLayerId() == null ? null : new LayerId(entity.targetLayerId());
        return new CameraPose(new AssetId(entity.assetId()), position, entity.aglMeters(), entity.yawDegrees(),
                entity.pitchDegrees(), entity.hfovDegrees(), targetLayerId, entity.source(),
                entity.rmsErrorPixels(), entity.updatedAt(), new UserId(entity.updatedBy()));
    }
}
