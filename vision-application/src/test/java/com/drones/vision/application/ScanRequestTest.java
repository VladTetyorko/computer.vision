package com.drones.vision.application;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScanRequestTest {

    @Test
    void rejectsAMissingZeroOrNegativeTimeout() {
        assertThrows(IllegalArgumentException.class, () -> new ScanRequest(null, Set.of()));
        assertThrows(IllegalArgumentException.class, () -> new ScanRequest(Duration.ZERO, Set.of()));
        assertThrows(IllegalArgumentException.class, () -> new ScanRequest(Duration.ofSeconds(-1), Set.of()));
    }

    @Test
    void rejectsMissingMethods() {
        assertThrows(IllegalArgumentException.class, () -> new ScanRequest(Duration.ofSeconds(4), null));
    }

    @Test
    void emptyMethodsMeansEveryRegisteredMechanismNotNone() {
        assertTrue(ScanRequest.defaults().methods().isEmpty());
    }

    @Test
    void defaultsToFourSeconds() {
        assertEquals(Duration.ofSeconds(4), ScanRequest.defaults().timeout());
    }

    @Test
    void copiesMethods() {
        Set<String> mutable = new HashSet<>(Set.of("mdns"));
        ScanRequest request = new ScanRequest(Duration.ofSeconds(4), mutable);

        mutable.add("onvif");

        assertEquals(Set.of("mdns"), request.methods());
    }
}
