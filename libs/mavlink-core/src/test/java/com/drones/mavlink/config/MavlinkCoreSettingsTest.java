package com.drones.mavlink.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Every default here must stay byte-identical to API.md's configuration table -- a mismatch is a real regression. */
class MavlinkCoreSettingsTest {

    @Test
    void defaultsMatchTheFrozenTable() {
        MavlinkCoreSettings settings = MavlinkCoreSettings.defaults();

        assertEquals(Duration.ofSeconds(1), settings.heartbeatPeriod());
        assertEquals(Duration.ofSeconds(5), settings.peerTimeout());
        assertEquals(Duration.ofSeconds(2), settings.commandTimeout());
        assertEquals(2, settings.commandRetries());
        assertEquals(Duration.ofSeconds(60), settings.commandDedupeWindow());
        assertEquals(33, settings.rc().overrideHz());
        assertEquals(10, settings.rc().minOverrideHz());
        assertEquals(50, settings.rc().maxOverrideHz());
        assertEquals(3, settings.rc().releaseFrames());
        assertEquals(Duration.ofSeconds(5), settings.closeJoinTimeout());
        assertEquals(64, settings.maxResyncBuffers());
        assertEquals(256, settings.dispatchQueueCapacity());
        assertEquals(Duration.ofMillis(1500), settings.mission().timeout());
        assertEquals(Duration.ofMillis(250), settings.mission().itemTimeout());
        assertEquals(5, settings.mission().retries());
        assertEquals(Duration.ofMillis(50), settings.ftp().timeout());
        assertEquals(6, settings.ftp().retries());
    }

    @Test
    void withMaxResyncBuffersReturnsACopyLeavingEverythingElseUnchanged() {
        MavlinkCoreSettings settings = MavlinkCoreSettings.defaults().withMaxResyncBuffers(128);
        assertEquals(128, settings.maxResyncBuffers());
        assertEquals(MavlinkCoreSettings.defaults().heartbeatPeriod(), settings.heartbeatPeriod());
    }

    @Test
    void withCommandRetriesReturnsACopy() {
        MavlinkCoreSettings settings = MavlinkCoreSettings.defaults().withCommandRetries(5);
        assertEquals(5, settings.commandRetries());
    }

    @Test
    void rejectsNonPositiveHeartbeatPeriod() {
        assertThrows(IllegalArgumentException.class, () -> MavlinkCoreSettings.defaults().withHeartbeatPeriod(Duration.ZERO));
    }

    @Test
    void rejectsNegativeCommandRetries() {
        assertThrows(IllegalArgumentException.class, () -> MavlinkCoreSettings.defaults().withCommandRetries(-1));
    }

    @Test
    void rejectsMaxResyncBuffersBelowOne() {
        assertThrows(IllegalArgumentException.class, () -> MavlinkCoreSettings.defaults().withMaxResyncBuffers(0));
    }

    @Test
    void rcClampsOverrideHzIntoItsOwnBounds() {
        MavlinkCoreSettings.Rc rc = new MavlinkCoreSettings.Rc(999, 10, 50, 3);
        assertEquals(50, rc.clampedOverrideHz());
    }

    @Test
    void rcRejectsMaxBelowMin() {
        assertThrows(IllegalArgumentException.class, () -> new MavlinkCoreSettings.Rc(33, 50, 10, 3));
    }
}
