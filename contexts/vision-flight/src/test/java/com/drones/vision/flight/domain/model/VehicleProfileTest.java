package com.drones.vision.flight.domain.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VehicleProfileTest {

    private static final Instant OBSERVED_AT = Instant.parse("2026-08-18T00:00:00Z");

    @Test
    void blankLinkKeyIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new VehicleProfile(" ", OBSERVED_AT, 7, null, null, null,
                null, List.of(), List.of(), List.of(), null, true, null));
    }

    @Test
    void sysidOutOfRangeIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new VehicleProfile("k", OBSERVED_AT, 256, null, null,
                null, null, List.of(), List.of(), List.of(), null, true, null));
        assertThrows(IllegalArgumentException.class, () -> new VehicleProfile("k", OBSERVED_AT, -1, null, null,
                null, null, List.of(), List.of(), List.of(), null, true, null));
    }

    @Test
    void completeProfileMustNotCarryAnIncompleteReason() {
        assertThrows(IllegalArgumentException.class, () -> new VehicleProfile("k", OBSERVED_AT, 7, null, null,
                null, null, List.of(), List.of(), List.of(), null, true, "should not be here"));
    }

    @Test
    void incompleteProfileMustSayWhy() {
        assertThrows(IllegalArgumentException.class, () -> new VehicleProfile("k", OBSERVED_AT, 7, null, null,
                null, null, List.of(), List.of(), List.of(), null, false, null));
        assertThrows(IllegalArgumentException.class, () -> new VehicleProfile("k", OBSERVED_AT, 7, null, null,
                null, null, List.of(), List.of(), List.of(), null, false, " "));
    }

    @Test
    void negativeCapabilityBitmaskIsAcceptedBecauseAHighBitSetIsALegitimateUint64Pattern() {
        VehicleProfile profile = new VehicleProfile("k", OBSERVED_AT, 7, "ardupilot", "4.5.7", "quadcopter",
                Long.MIN_VALUE, List.of("MAVLINK2"), List.of(), List.of(), null, true, null);
        assertTrue(profile.capabilityBitmask() < 0);
    }

    @Test
    void nullListsDefaultToEmptyRatherThanThrowing() {
        VehicleProfile profile = new VehicleProfile("k", OBSERVED_AT, null, null, null, null, null, null, null,
                null, null, true, null);
        assertTrue(profile.capabilityFlags().isEmpty());
        assertTrue(profile.messages().isEmpty());
        assertTrue(profile.parameters().isEmpty());
    }
}
