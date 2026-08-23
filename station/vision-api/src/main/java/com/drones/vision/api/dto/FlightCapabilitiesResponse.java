package com.drones.vision.api.dto;

import com.drones.vision.flight.domain.model.FlightCapability;

import java.util.List;

/**
 * Response body for {@code GET /api/assets/{id}/flight-capabilities} (docs/plans/active/DRONE-INFRA-PLAN.md I-e
 * Stage 2's frozen wire contract) — mirrors domain {@link FlightCapability} field-for-field so a
 * driving adapter can decide which flight-command controls to show. No {@code @JsonInclude} — every
 * field is always present ({@code selectableModes} is an empty list, never absent, when mode select
 * is unsupported).
 *
 * @param commandable         whether any command may be attempted against the vehicle at all
 * @param armSupported        whether arm/disarm may be attempted
 * @param modeSelectSupported whether mode select may be attempted
 * @param selectableModes     the mode names {@code setMode} accepts (empty when unsupported)
 * @param vehicleKind         what the vehicle most recently reported itself to be: {@code "COPTER"},
 *                            {@code "PLANE"}, {@code "ROVER"}, or {@code "UNKNOWN"} — never a guess
 *                            (docs/plans/active/VEHICLE-CONTROL-PROFILES-CONTEXT.md §3.3). Lets a
 *                            client label the vehicle honestly before any control is engaged; the
 *                            manual-control relay resolves the same fact independently from its own
 *                            live link, so a stale read here cannot mis-shape a session
 */
public record FlightCapabilitiesResponse(boolean commandable, boolean armSupported,
                                         boolean modeSelectSupported, List<String> selectableModes,
                                         String vehicleKind) {

    public static FlightCapabilitiesResponse from(FlightCapability capability) {
        return new FlightCapabilitiesResponse(capability.commandable(), capability.armSupported(),
                capability.modeSelectSupported(), capability.selectableModes(), capability.vehicleKind().name());
    }
}
