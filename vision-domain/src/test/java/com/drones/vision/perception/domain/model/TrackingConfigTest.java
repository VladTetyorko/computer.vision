package com.drones.vision.perception.domain.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TrackingConfigTest {

    @Test
    void offIsModeOffWithNoLock() {
        TrackingConfig off = TrackingConfig.off();

        assertEquals(TrackingMode.OFF, off.mode());
        assertNull(off.lock());
    }

    @Test
    void defaultsIsModeAssociateWithNoLock() {
        TrackingConfig defaults = TrackingConfig.defaults();

        assertEquals(TrackingMode.ASSOCIATE, defaults.mode());
        assertNull(defaults.lock());
    }

    @Test
    void offAndDefaultsShareTheSameCadences() {
        TrackingConfig off = TrackingConfig.off();
        TrackingConfig defaults = TrackingConfig.defaults();

        assertEquals(off.verifyEveryMillis(), defaults.verifyEveryMillis());
        assertEquals(off.followFps(), defaults.followFps());
        assertEquals(off.redetectIouPercent(), defaults.redetectIouPercent());
        assertEquals(off.maxAgeFrames(), defaults.maxAgeFrames());
        assertEquals(off.minHits(), defaults.minHits());
    }

    @Test
    void rejectsNullModeOrEngineId() {
        assertThrows(IllegalArgumentException.class,
                () -> new TrackingConfig(null, "", 2000, 15, 30, 30, 3, null));
        assertThrows(IllegalArgumentException.class,
                () -> new TrackingConfig(TrackingMode.OFF, null, 2000, 15, 30, 30, 3, null));
    }

    @Test
    void rejectsNonPositiveCadences() {
        assertThrows(IllegalArgumentException.class,
                () -> new TrackingConfig(TrackingMode.OFF, "", 0, 15, 30, 30, 3, null));
        assertThrows(IllegalArgumentException.class,
                () -> new TrackingConfig(TrackingMode.OFF, "", 2000, 0, 30, 30, 3, null));
        assertThrows(IllegalArgumentException.class,
                () -> new TrackingConfig(TrackingMode.OFF, "", 2000, 15, 30, 0, 3, null));
        assertThrows(IllegalArgumentException.class,
                () -> new TrackingConfig(TrackingMode.OFF, "", 2000, 15, 30, 30, 0, null));
    }

    @Test
    void rejectsOutOfRangeRedetectIouPercent() {
        assertThrows(IllegalArgumentException.class,
                () -> new TrackingConfig(TrackingMode.OFF, "", 2000, 15, -1, 30, 3, null));
        assertThrows(IllegalArgumentException.class,
                () -> new TrackingConfig(TrackingMode.OFF, "", 2000, 15, 101, 30, 3, null));
    }

    @Test
    void acceptsBoundaryRedetectIouPercent() {
        new TrackingConfig(TrackingMode.OFF, "", 2000, 15, 0, 30, 3, null);
        new TrackingConfig(TrackingMode.OFF, "", 2000, 15, 100, 30, 3, null);
    }

    @Test
    void acceptsAnExplicitLock() {
        TargetLock lock = new TargetLock(1L, 7L, null, null, false);

        TrackingConfig config = new TrackingConfig(TrackingMode.FOLLOW, "lk", 2000, 15, 30, 30, 3, lock);

        assertEquals(lock, config.lock());
    }
}
