package com.drones.vision.perception.domain.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RegionIngestSpecTest {

    private static final RegionBounds BOUNDS = new RegionBounds(50.4020, 50.3860, 30.6400, 30.6120);

    @Test
    void storesEveryField() {
        RegionIngestSpec spec = new RegionIngestSpec("kyiv-pozniaky", "Poznyaky", BOUNDS, 17);

        assertEquals("kyiv-pozniaky", spec.regionId());
        assertEquals("Poznyaky", spec.name());
        assertEquals(BOUNDS, spec.bounds());
        assertEquals(17, spec.zoom());
    }

    @Test
    void rejectsBlankRegionIdAndName() {
        assertThrows(IllegalArgumentException.class, () -> new RegionIngestSpec("", "Poznyaky", BOUNDS, 17));
        assertThrows(IllegalArgumentException.class, () -> new RegionIngestSpec("kyiv-pozniaky", " ", BOUNDS, 17));
    }

    @Test
    void rejectsNonKebabRegionId() {
        assertThrows(IllegalArgumentException.class, () -> new RegionIngestSpec("Kyiv_Pozniaky", "x", BOUNDS, 17));
        assertThrows(IllegalArgumentException.class, () -> new RegionIngestSpec("-leading-hyphen", "x", BOUNDS, 17));
        assertThrows(IllegalArgumentException.class, () -> new RegionIngestSpec("trailing-hyphen-", "x", BOUNDS, 17));
    }

    @Test
    void rejectsZoomOutsideFrozenRange() {
        assertThrows(IllegalArgumentException.class,
                () -> new RegionIngestSpec("kyiv-pozniaky", "x", BOUNDS, RegionIngestSpec.ZOOM_MIN - 1));
        assertThrows(IllegalArgumentException.class,
                () -> new RegionIngestSpec("kyiv-pozniaky", "x", BOUNDS, RegionIngestSpec.ZOOM_MAX + 1));
    }

    @Test
    void acceptsZoomAtFrozenBoundaries() {
        new RegionIngestSpec("kyiv-pozniaky", "x", BOUNDS, RegionIngestSpec.ZOOM_MIN);
        new RegionIngestSpec("kyiv-pozniaky", "x", BOUNDS, RegionIngestSpec.ZOOM_MAX);
    }
}
