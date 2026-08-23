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
 * @param vehicleKind          what kind of machine this is, as the vehicle itself most recently
 *                             reported it — {@link VehicleKind#UNKNOWN} when never heard or not
 *                             recognized, never a guess. A driving adapter uses it to label and
 *                             shape its controls (docs/plans/active/VEHICLE-CONTROL-PROFILES-CONTEXT.md
 *                             §3.3); the manual-control relay resolves the same fact independently
 *                             from the engaged link, so a stale read here can never mis-shape a
 *                             live session
 */
public record FlightCapability(boolean commandable, boolean armSupported, boolean modeSelectSupported,
                               List<String> selectableModes, VehicleKind vehicleKind) {

    public FlightCapability {
        selectableModes = List.copyOf(selectableModes);
        if (vehicleKind == null) {
            throw new IllegalArgumentException("FlightCapability vehicleKind must not be null (use UNKNOWN)");
        }
    }

    /**
     * The all-{@code false}, empty-mode-list snapshot for a vehicle this platform cannot command.
     * Its {@code vehicleKind} is {@link VehicleKind#UNKNOWN}: a vehicle we cannot command is one we
     * have not heard enough from to classify either.
     */
    public static FlightCapability notCommandable() {
        return new FlightCapability(false, false, false, List.of(), VehicleKind.UNKNOWN);
    }
}
