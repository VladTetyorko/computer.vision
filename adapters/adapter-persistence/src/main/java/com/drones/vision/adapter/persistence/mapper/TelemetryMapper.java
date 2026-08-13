package com.drones.vision.adapter.persistence.mapper;

import com.drones.vision.adapter.persistence.entity.TelemetrySampleEntity;
import com.drones.vision.kernel.DeviceId;
import com.drones.vision.kernel.Telemetry;
import com.drones.vision.kernel.UsageId;

import java.util.UUID;

/**
 * {@link Telemetry} &harr; {@link TelemetrySampleEntity} mapping, extracted from {@code
 * JpaTelemetryRepository} (docs/plans/active/LAYERING-REFACTOR-PLAN.md §3/§7 row C).
 *
 * <p>{@link #toEntity} invents a synthetic {@code UUID} id at mapping time — {@link Telemetry}
 * itself carries no identity (an append-only sample, not an aggregate), so there is nothing
 * domain-side to derive a primary key from; the id never surfaces back through the port.
 */
public final class TelemetryMapper {

    private TelemetryMapper() {
    }

    public static TelemetrySampleEntity toEntity(UsageId usageId, Telemetry telemetry) {
        return new TelemetrySampleEntity(UUID.randomUUID(), usageId.value(), telemetry.deviceId().value(),
                telemetry.at(), telemetry.latitude(), telemetry.longitude(), telemetry.altitudeMeters(),
                telemetry.headingDegrees(), telemetry.batteryPercent(), telemetry.extra(), telemetry.flightState());
    }

    public static Telemetry toDomain(TelemetrySampleEntity entity) {
        return new Telemetry(new DeviceId(entity.deviceId()), entity.at(), entity.latitude(), entity.longitude(),
                entity.altitudeMeters(), entity.headingDegrees(), entity.batteryPercent(), entity.extra(),
                entity.flightState());
    }
}
