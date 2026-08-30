package com.drones.vision.warehouse.domain.model;

import com.drones.vision.kernel.UserId;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CustodyTest {

    @Test
    void noneHasNoCustodianLocationOrSince() {
        assertNull(Custody.NONE.custodianId());
        assertNull(Custody.NONE.location());
        assertNull(Custody.NONE.since());
    }

    @Test
    void requiresSinceWhenCustodianIsSet() {
        assertThrows(IllegalArgumentException.class, () -> new Custody(UserId.random(), "Hangar 2", null));
    }

    @Test
    void clearsSinceWhenNoCustodian() {
        Custody custody = new Custody(null, "Hangar 2", Instant.now());

        assertNull(custody.since());
    }

    @Test
    void blankLocationNormalizesToNull() {
        Custody custody = new Custody(UserId.random(), "  ", Instant.now());

        assertNull(custody.location());
    }
}
