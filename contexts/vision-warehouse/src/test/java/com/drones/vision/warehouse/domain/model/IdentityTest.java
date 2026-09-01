package com.drones.vision.warehouse.domain.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class IdentityTest {

    @Test
    void blankFieldsNormalizeToNull() {
        Identity identity = new Identity(" ", "", "\t", null);

        assertNull(identity.serialNumber());
        assertNull(identity.make());
        assertNull(identity.model());
        assertNull(identity.registration());
    }

    @Test
    void keepsNonBlankFields() {
        Identity identity = new Identity("SN-1", "Acme", "X1", "N123AB");

        assertEquals("SN-1", identity.serialNumber());
        assertEquals("Acme", identity.make());
        assertEquals("X1", identity.model());
        assertEquals("N123AB", identity.registration());
    }

    @Test
    void noneIsAllNull() {
        assertNull(Identity.NONE.serialNumber());
        assertNull(Identity.NONE.make());
        assertNull(Identity.NONE.model());
        assertNull(Identity.NONE.registration());
    }
}
