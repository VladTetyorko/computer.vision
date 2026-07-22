package com.drones.vision.application;

import com.drones.vision.domain.model.DiscoveredDevice;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScanResultTest {

    private static DiscoveredDevice candidate() {
        return new DiscoveredDevice("mdns", "cam", URI.create("http://192.168.1.9"), null, null, Map.of());
    }

    @Test
    void rejectsMissingComponents() {
        assertThrows(IllegalArgumentException.class, () -> new ScanResult(null, Set.of()));
        assertThrows(IllegalArgumentException.class, () -> new ScanResult(List.of(), null));
    }

    @Test
    void anEmptyResultIsValidBecauseAnEmptyNetworkIsNotAnError() {
        ScanResult result = new ScanResult(List.of(), Set.of());

        assertTrue(result.devices().isEmpty());
        assertTrue(result.failedMethods().isEmpty());
    }

    @Test
    void carriesFailedMethodsSeparatelyFromFindings() {
        // "Found nothing" and "the scanner broke" must stay distinguishable.
        ScanResult result = new ScanResult(List.of(), Set.of("onvif"));

        assertEquals(Set.of("onvif"), result.failedMethods());
    }

    @Test
    void copiesDevices() {
        List<DiscoveredDevice> mutable = new ArrayList<>(List.of(candidate()));
        ScanResult result = new ScanResult(mutable, Set.of());

        mutable.add(candidate());

        assertEquals(1, result.devices().size());
    }
}
