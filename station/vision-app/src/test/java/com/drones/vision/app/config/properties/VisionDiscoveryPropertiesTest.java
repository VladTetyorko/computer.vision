package com.drones.vision.app.config.properties;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    /**
     * docs/plans/active/ZERO-CONFIG-ONBOARDING-CONTEXT.md &sect;11, Z2c — {@code lobby}/{@code
     * inbox} default as a whole when absent, exactly as {@code mdns}/{@code v4l2} already do.
     */
    @Test
    void lobbyAndInboxDefaultWhenAbsent() {
        VisionDiscoveryProperties properties = new VisionDiscoveryProperties(14550);

        assertTrue(properties.lobby().enabled());
        assertTrue(properties.inbox().enabled());
        assertEquals(30, properties.inbox().sweepSeconds());
        assertEquals(5, properties.inbox().scanTimeoutSeconds());
    }

    @Test
    void explicitLobbyAndInboxAreCarriedThrough() {
        VisionDiscoveryProperties properties = new VisionDiscoveryProperties(14550, null, null,
                new VisionDiscoveryProperties.Lobby(false), new VisionDiscoveryProperties.Inbox(false, 60, 10), null,
                null);

        assertFalse(properties.lobby().enabled());
        assertFalse(properties.inbox().enabled());
        assertEquals(60, properties.inbox().sweepSeconds());
        assertEquals(10, properties.inbox().scanTimeoutSeconds());
    }

    /**
     * docs/plans/active/ZERO-CONFIG-ONBOARDING-CONTEXT.md §11 "Z3 amendment" -- {@code mediamtx}
     * defaults as a whole when absent, exactly as {@code lobby}/{@code inbox} already do.
     */
    @Test
    void mediamtxDefaultsWhenAbsent() {
        VisionDiscoveryProperties properties = new VisionDiscoveryProperties(14550);

        assertTrue(properties.mediamtx().enabled());
        assertEquals("ingest/", properties.mediamtx().pathPrefix());
    }

    @Test
    void explicitMediamtxIsCarriedThrough() {
        VisionDiscoveryProperties properties = new VisionDiscoveryProperties(14550, null, null, null, null,
                new VisionDiscoveryProperties.Mediamtx(false, "custom/"), null);

        assertFalse(properties.mediamtx().enabled());
        assertEquals("custom/", properties.mediamtx().pathPrefix());
    }

    @Test
    void blankMediamtxPathPrefixIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new VisionDiscoveryProperties.Mediamtx(true, ""));
        assertThrows(IllegalArgumentException.class, () -> new VisionDiscoveryProperties.Mediamtx(true, null));
    }

    @Test
    void zeroOrNegativeSweepSecondsIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new VisionDiscoveryProperties.Inbox(true, 0, 5));
        assertThrows(IllegalArgumentException.class,
                () -> new VisionDiscoveryProperties.Inbox(true, -1, 5));
    }

    @Test
    void zeroOrNegativeScanTimeoutSecondsIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new VisionDiscoveryProperties.Inbox(true, 30, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new VisionDiscoveryProperties.Inbox(true, 30, -1));
    }
}
