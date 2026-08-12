package com.drones.vision.warehouse.application.discovery;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiscoveryScanSpecTest {

    @Test
    void rejectsAMissingZeroOrNegativeTimeout() {
        assertThrows(IllegalArgumentException.class, () -> new DiscoveryScanSpec(null, Set.of()));
        assertThrows(IllegalArgumentException.class, () -> new DiscoveryScanSpec(Duration.ZERO, Set.of()));
        assertThrows(IllegalArgumentException.class, () -> new DiscoveryScanSpec(Duration.ofSeconds(-1), Set.of()));
    }

    @Test
    void rejectsMissingMethods() {
        assertThrows(IllegalArgumentException.class, () -> new DiscoveryScanSpec(Duration.ofSeconds(4), null));
    }

    @Test
    void emptyMethodsMeansEveryRegisteredMechanismNotNone() {
        assertTrue(DiscoveryScanSpec.defaults().methods().isEmpty());
    }

    @Test
    void defaultsToFourSeconds() {
        assertEquals(Duration.ofSeconds(4), DiscoveryScanSpec.defaults().timeout());
    }

    @Test
    void copiesMethods() {
        Set<String> mutable = new HashSet<>(Set.of("mdns"));
        DiscoveryScanSpec request = new DiscoveryScanSpec(Duration.ofSeconds(4), mutable);

        mutable.add("onvif");

        assertEquals(Set.of("mdns"), request.methods());
    }
}
