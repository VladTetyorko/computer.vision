package com.drones.vision.warehouse.domain.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MaintenanceKindTest {

    @Test
    void groundingAndInspectionDueBlockFlight() {
        assertTrue(MaintenanceKind.GROUNDING.blocksFlight());
        assertTrue(MaintenanceKind.INSPECTION_DUE.blocksFlight());
    }

    @Test
    void repairAndNoteDoNotBlockFlight() {
        assertFalse(MaintenanceKind.REPAIR.blocksFlight());
        assertFalse(MaintenanceKind.NOTE.blocksFlight());
    }
}
