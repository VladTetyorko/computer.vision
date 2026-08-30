package com.drones.vision.api.dto;

import com.drones.vision.flight.domain.model.VehicleProfile;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Nested element on {@link AssetSummaryResponse}/{@link AssetDetailsResponse} — the asset's most
 * recently observed firmware, joined from vision-flight's {@link VehicleProfile} at the vision-api
 * layer, not inside vision-warehouse (docs/plans/active/WAREHOUSE-UX-PLAN.md D5: "firmware stays
 * flight-owned; the table joins it. Warehouse must not read {@code vehicle_profiles}."). See {@code
 * com.drones.vision.api.support.AssetRowFacts#firmwareOf} for the join itself — the same "compose
 * two contexts' reads at the vision-api layer" trick {@code ReadinessController} already uses.
 *
 * <p>Omitted entirely from the enclosing response (rather than present with two {@code null}
 * fields) when the asset's devices were never probed at all — see {@link AssetSummaryResponse}'s own
 * {@code NON_NULL} convention.
 *
 * @param name    {@code "ardupilot"} | {@code "generic"} | {@code "px4"}, or {@code null} if the
 *                probe answered but firmware was not identified
 * @param version the firmware version string, or {@code null} if not identified
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FirmwareResponse(String name, String version) {

    /**
     * Maps a {@link VehicleProfile} to its wire representation.
     *
     * @param profile the profile to map
     * @return the response body element for {@code profile}
     */
    public static FirmwareResponse from(VehicleProfile profile) {
        return new FirmwareResponse(profile.firmware(), profile.firmwareVersion());
    }
}
