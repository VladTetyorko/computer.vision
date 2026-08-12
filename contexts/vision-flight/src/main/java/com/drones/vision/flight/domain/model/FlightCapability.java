package com.drones.vision.flight.domain.model;

import java.util.List;

/**
 * A best-effort snapshot of what flight commands the aircraft behind a device can be sent, derived
 * from the firmware/vehicle-family this platform has most recently heard it report (docs/
 * DRONE-INFRA-PLAN.md I-e Stage 2). It is honest, not aspirational: an unheard, unsupported, or
 * non-commandable vehicle reports everything {@code false} and an empty mode list rather than
 * guessing. A driving adapter uses it to decide which command controls to show at all — see {@link
 * com.drones.vision.flight.domain.port.FlightCommandPort#capabilities(Device)}.
 *
 * @param commandable          whether this platform can attempt any command against the vehicle at
 *                             all (an unrecognized/never-heard firmware, or one whose RC link is
 *                             known not to process MAVLink commands such as Betaflight, is
 *                             {@code false} — with everything else {@code false}/empty too)
 * @param armSupported         whether {@link
 *                             com.drones.vision.flight.domain.port.FlightCommandPort#arm(Device,
 *                             boolean)}/{@code disarm} may be attempted; never {@code true} when
 *                             {@code commandable} is {@code false}
 * @param modeSelectSupported  whether {@link
 *                             com.drones.vision.flight.domain.port.FlightCommandPort#setMode(Device,
 *                             String)} may be attempted; never {@code true} when {@code
 *                             commandable} is {@code false}
 * @param selectableModes      the mode names {@code setMode} accepts for this vehicle (empty when
 *                             mode select is unsupported); non-null, defensively copied
 */
public record FlightCapability(boolean commandable, boolean armSupported, boolean modeSelectSupported,
                               List<String> selectableModes) {

    public FlightCapability {
        selectableModes = List.copyOf(selectableModes);
    }

    /** The all-{@code false}, empty-mode-list snapshot for a vehicle this platform cannot command. */
    public static FlightCapability notCommandable() {
        return new FlightCapability(false, false, false, List.of());
    }
}
