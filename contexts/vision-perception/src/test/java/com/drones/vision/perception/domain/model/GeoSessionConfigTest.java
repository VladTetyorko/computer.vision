package com.drones.vision.perception.domain.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GeoSessionConfigTest {

    @Test
    void emptyRegionIdMeansSearchEveryReadyRegion() {
        GeoSessionConfig config = new GeoSessionConfig("", 0f, null);

        assertEquals("", config.regionId());
        assertNull(config.prior());
    }

    @Test
    void storesAPrior() {
        GeoPrior prior = new GeoPrior(50.39, 30.63, 200.0);
        GeoSessionConfig config = new GeoSessionConfig("kyiv-pozniaky", 1.0f, prior);

        assertEquals(prior, config.prior());
        assertEquals(1.0f, config.targetFps());
    }

    @Test
    void rejectsNullRegionId() {
        assertThrows(IllegalArgumentException.class, () -> new GeoSessionConfig(null, 1.0f, null));
    }

    @Test
    void rejectsNonFiniteTargetFps() {
        assertThrows(IllegalArgumentException.class, () -> new GeoSessionConfig("", Float.NaN, null));
        assertThrows(IllegalArgumentException.class,
                () -> new GeoSessionConfig("", Float.POSITIVE_INFINITY, null));
    }
}
