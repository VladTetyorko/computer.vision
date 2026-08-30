package com.drones.vision.warehouse.domain.model;

import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.UserId;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MaintenanceRecordTest {

    private static MaintenanceRecord open() {
        return new MaintenanceRecord(MaintenanceId.random(), AssetId.random(), MaintenanceKind.GROUNDING,
                Instant.now(), null, UserId.random(), "prop nicked", null);
    }

    @Test
    void isOpenWhenClosedAtIsNull() {
        assertTrue(open().isOpen());
    }

    @Test
    void closeSetsClosedAtAndMakesItNotOpen() {
        MaintenanceRecord record = open();
        Instant closedAt = record.openedAt().plusSeconds(60);

        MaintenanceRecord closed = record.close(closedAt);

        assertEquals(closedAt, closed.closedAt());
        assertFalse(closed.isOpen());
        assertTrue(record.isOpen(), "original instance must be unchanged");
    }

    @Test
    void closeRejectsNull() {
        assertThrows(IllegalArgumentException.class, () -> open().close(null));
    }

    @Test
    void rejectsClosedAtBeforeOpenedAt() {
        Instant openedAt = Instant.now();
        Instant closedAt = openedAt.minusSeconds(60);

        assertThrows(IllegalArgumentException.class, () -> new MaintenanceRecord(MaintenanceId.random(),
                AssetId.random(), MaintenanceKind.GROUNDING, openedAt, closedAt, UserId.random(), "summary", null));
    }

    @Test
    void rejectsInvalidArguments() {
        MaintenanceId id = MaintenanceId.random();
        AssetId assetId = AssetId.random();
        UserId openedBy = UserId.random();
        Instant now = Instant.now();

        assertThrows(IllegalArgumentException.class,
                () -> new MaintenanceRecord(null, assetId, MaintenanceKind.GROUNDING, now, null, openedBy, "s", null));
        assertThrows(IllegalArgumentException.class,
                () -> new MaintenanceRecord(id, null, MaintenanceKind.GROUNDING, now, null, openedBy, "s", null));
        assertThrows(IllegalArgumentException.class,
                () -> new MaintenanceRecord(id, assetId, null, now, null, openedBy, "s", null));
        assertThrows(IllegalArgumentException.class,
                () -> new MaintenanceRecord(id, assetId, MaintenanceKind.GROUNDING, null, null, openedBy, "s", null));
        assertThrows(IllegalArgumentException.class,
                () -> new MaintenanceRecord(id, assetId, MaintenanceKind.GROUNDING, now, null, null, "s", null));
        assertThrows(IllegalArgumentException.class,
                () -> new MaintenanceRecord(id, assetId, MaintenanceKind.GROUNDING, now, null, openedBy, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> new MaintenanceRecord(id, assetId, MaintenanceKind.GROUNDING, now, null, openedBy, "", null));
        assertThrows(IllegalArgumentException.class,
                () -> new MaintenanceRecord(id, assetId, MaintenanceKind.GROUNDING, now, null, openedBy, "s", -1L));
    }
}
