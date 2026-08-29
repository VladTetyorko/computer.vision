package com.drones.vision.warehouse.domain.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InventoryStateTest {

    @Test
    void storableStatesAreStockMaintenanceAndRetired() {
        assertTrue(InventoryState.IN_STOCK.storable());
        assertTrue(InventoryState.MAINTENANCE.storable());
        assertTrue(InventoryState.RETIRED.storable());
    }

    @Test
    void derivedStatesAreNotStorable() {
        assertFalse(InventoryState.ISSUED.storable());
        assertFalse(InventoryState.IN_FIELD.storable());
    }
}
