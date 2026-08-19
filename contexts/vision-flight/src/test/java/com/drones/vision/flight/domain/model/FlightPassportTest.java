package com.drones.vision.flight.domain.model;

import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.UsageId;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FlightPassportTest {

    @Test
    void nullUsageIdIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new FlightPassport(null, AssetId.random(), null, null));
    }

    @Test
    void nullAssetIdIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new FlightPassport(UsageId.random(), null, null, null));
    }

    @Test
    void bothProfilesMayBeNullWhenNeitherPhaseWasCapturedYet() {
        FlightPassport passport = new FlightPassport(UsageId.random(), AssetId.random(), null, null);
        assertNull(passport.preflightProfile());
        assertNull(passport.postflightProfile());
    }
}
