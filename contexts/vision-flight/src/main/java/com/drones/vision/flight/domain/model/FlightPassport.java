package com.drones.vision.flight.domain.model;

import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.UsageId;

/**
 * One flight's forensic evidence (docs/plans/active/DRONE-ONBOARDING-PLAN.md section 2.4/O11): the
 * {@link VehicleProfile} snapshot taken at {@link FlightPhase#PREFLIGHT} and, once the aircraft has
 * landed, the one taken at {@link FlightPhase#POSTFLIGHT} -- what the aircraft actually was, at the
 * start and end of this specific usage.
 *
 * <p>Read model only; nothing here is persisted as its own row (each half is a {@link
 * VehicleProfile} row tagged with this usage and phase -- see {@code
 * VehicleProfileRepositoryPort#save(com.drones.vision.kernel.DeviceId, UsageId, FlightPhase,
 * VehicleProfile)}). Either profile is {@code null} rather than fabricated when the corresponding
 * phase has not been captured yet (C7) -- a usage still {@code IN_FLIGHT} legitimately has a
 * preflight profile and no postflight one.
 *
 * @param usageId           the {@code AssetUsage} (vision-warehouse) this passport belongs to
 * @param assetId           the asset that usage belongs to
 * @param preflightProfile  the snapshot captured at {@code PREFLIGHT}, or {@code null} if none has
 *                          been captured yet
 * @param postflightProfile the snapshot captured at {@code POSTFLIGHT}, or {@code null} if the
 *                          flight has not reached that phase yet, or none was captured
 */
public record FlightPassport(UsageId usageId, AssetId assetId, VehicleProfile preflightProfile,
                              VehicleProfile postflightProfile) {

    public FlightPassport {
        if (usageId == null) {
            throw new IllegalArgumentException("FlightPassport usageId must not be null");
        }
        if (assetId == null) {
            throw new IllegalArgumentException("FlightPassport assetId must not be null");
        }
    }
}
