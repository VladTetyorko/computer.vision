package com.drones.vision.api.dto;

import com.drones.vision.domain.model.FlightCapability;

import java.util.List;

/**
 * Response body for {@code GET /api/assets/{id}/flight-capabilities} (docs/DRONE-INFRA-PLAN.md I-e
 * Stage 2's frozen wire contract) — mirrors domain {@link FlightCapability} field-for-field so a
 * driving adapter can decide which flight-command controls to show. No {@code @JsonInclude} — every
 * field is always present ({@code selectableModes} is an empty list, never absent, when mode select
 * is unsupported).
 *
 * @param commandable         whether any command may be attempted against the vehicle at all
 * @param armSupported        whether arm/disarm may be attempted
 * @param modeSelectSupported whether mode select may be attempted
 * @param selectableModes     the mode names {@code setMode} accepts (empty when unsupported)
 */
public record FlightCapabilitiesResponse(boolean commandable, boolean armSupported,
                                         boolean modeSelectSupported, List<String> selectableModes) {

    public static FlightCapabilitiesResponse from(FlightCapability capability) {
        return new FlightCapabilitiesResponse(capability.commandable(), capability.armSupported(),
                capability.modeSelectSupported(), capability.selectableModes());
    }
}
