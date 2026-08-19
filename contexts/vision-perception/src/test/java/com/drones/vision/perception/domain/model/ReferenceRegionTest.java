package com.drones.vision.perception.domain.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertThrows;

class ReferenceRegionTest {

    private static final RegionBounds BOUNDS = new RegionBounds(50.4020, 50.3860, 30.6400, 30.6120);
    private static final ReferenceIndexSummary SUMMARY = new ReferenceIndexSummary("kyiv-pozniaky", "Poznyaky",
            BOUNDS, 17, Instant.parse("2026-08-20T09:12:03Z"), 312, 312, 512, "eigenplaces_r18_512", 0.62, 0.03,
            0.55, 43.0, 12_000_000L, 4);

    @Test
    void buildingAndFailedRequireNullSummary() {
        new ReferenceRegion("kyiv-pozniaky", "Poznyaky", BOUNDS, 17, RegionStatus.BUILDING, null);
        new ReferenceRegion("kyiv-pozniaky", "Poznyaky", BOUNDS, 17, RegionStatus.FAILED, null);

        assertThrows(IllegalArgumentException.class,
                () -> new ReferenceRegion("kyiv-pozniaky", "Poznyaky", BOUNDS, 17, RegionStatus.BUILDING, SUMMARY));
        assertThrows(IllegalArgumentException.class,
                () -> new ReferenceRegion("kyiv-pozniaky", "Poznyaky", BOUNDS, 17, RegionStatus.FAILED, SUMMARY));
    }

    @Test
    void readyAndNeverAcceptRequireNonNullSummary() {
        new ReferenceRegion("kyiv-pozniaky", "Poznyaky", BOUNDS, 17, RegionStatus.READY, SUMMARY);
        new ReferenceRegion("kyiv-pozniaky", "Poznyaky", BOUNDS, 17, RegionStatus.NEVER_ACCEPT, SUMMARY);

        assertThrows(IllegalArgumentException.class,
                () -> new ReferenceRegion("kyiv-pozniaky", "Poznyaky", BOUNDS, 17, RegionStatus.READY, null));
        assertThrows(IllegalArgumentException.class,
                () -> new ReferenceRegion("kyiv-pozniaky", "Poznyaky", BOUNDS, 17, RegionStatus.NEVER_ACCEPT, null));
    }
}
