package com.drones.vision.perception.domain.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReferenceIndexSummaryTest {

    private static final RegionBounds BOUNDS = new RegionBounds(50.4020, 50.3860, 30.6400, 30.6120);
    private static final Instant BUILT_AT = Instant.parse("2026-08-20T09:12:03Z");

    private static ReferenceIndexSummary summary(int tileCount, int neverAcceptCells) {
        return new ReferenceIndexSummary("kyiv-pozniaky", "Poznyaky", BOUNDS, 17, BUILT_AT, tileCount, 312 * 4,
                512, "eigenplaces_r18_512", 0.62, 0.03, 0.55, 43.0, 12_000_000L, neverAcceptCells);
    }

    @Test
    void impliedStatusIsReadyWhenAnyCellIsUsable() {
        assertEquals(RegionStatus.READY, summary(312, 0).impliedStatus());
        assertEquals(RegionStatus.READY, summary(312, 4).impliedStatus());
        assertEquals(RegionStatus.READY, summary(312, 311).impliedStatus());
    }

    @Test
    void impliedStatusIsNeverAcceptWhenEveryCellIsNeverAccept() {
        assertEquals(RegionStatus.NEVER_ACCEPT, summary(312, 312).impliedStatus());
    }

    @Test
    void impliedStatusIsReadyForAnEmptyRegion() {
        // 0 tiles / 0 never-accept: the "every cell is never-accept" rule requires at least one cell.
        assertEquals(RegionStatus.READY, summary(0, 0).impliedStatus());
    }

    @Test
    void rejectsBlankIdentityFields() {
        assertThrows(IllegalArgumentException.class, () -> new ReferenceIndexSummary("", "n", BOUNDS, 17, BUILT_AT,
                1, 1, 1, "enc", 0, 0, 0, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new ReferenceIndexSummary("id", " ", BOUNDS, 17, BUILT_AT,
                1, 1, 1, "enc", 0, 0, 0, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new ReferenceIndexSummary("id", "n", BOUNDS, 17, BUILT_AT,
                1, 1, 1, "", 0, 0, 0, 0, 0, 0));
    }

    @Test
    void rejectsNegativeCounts() {
        assertThrows(IllegalArgumentException.class, () -> new ReferenceIndexSummary("id", "n", BOUNDS, 17, BUILT_AT,
                -1, 1, 1, "enc", 0, 0, 0, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new ReferenceIndexSummary("id", "n", BOUNDS, 17, BUILT_AT,
                1, 1, 1, "enc", 0, 0, 0, 0, 0, -1));
    }
}
