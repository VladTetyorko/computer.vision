package com.drones.vision.app.config.properties;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Plain unit tests (no Spring context) for {@link VisionDiscoveryProperties}'s compact-constructor
 * validation — mirrors {@link VisionCvPropertiesTest}'s own no-context style for a record-plus-
 * {@code @DefaultValue} properties class.
 */
class VisionDiscoveryPropertiesTest {

    @Test
    void mavlinkPortIsCarriedThrough() {
        VisionDiscoveryProperties properties = new VisionDiscoveryProperties(14550);
        assertEquals(14550, properties.mavlinkPort());
    }

    @Test
    void zeroPortIsRejected() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new VisionDiscoveryProperties(0));
        assertEquals("vision.discovery.mavlink-port must be a valid UDP port (1-65535), was 0", ex.getMessage());
    }

    @Test
    void negativePortIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new VisionDiscoveryProperties(-1));
    }

    @Test
    void portAboveMaximumIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new VisionDiscoveryProperties(65_536));
    }

    @Test
    void boundaryPortsAreAccepted() {
        assertDoesNotThrow(() -> new VisionDiscoveryProperties(1));
        assertDoesNotThrow(() -> new VisionDiscoveryProperties(65_535));
    }

    @Test
    void defaultMatchesApplicationPropertiesDocumentedValue() {
        // VisionDiscoveryProperties is only ever constructed with mavlinkPort explicitly (this
        // record has no no-arg form) -- this test pins the *value* the @DefaultValue annotation
        // declares (see the class itself), which is what application.yaml's commented
        // vision.discovery.mavlink-port line documents as "the default".
        assertEquals(14550, Integer.parseInt(VisionDiscoveryProperties.DEFAULT_MAVLINK_PORT));
    }
}
