package com.drones.vision.map.domain.model;

import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.GeoPosition;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertThrows;

class TrackPointTest {

    private static final GeoPosition POSITION = new GeoPosition(50.45, 30.52, null);

    @Test
    void rejectsNullAssetId() {
        assertThrows(IllegalArgumentException.class,
                () -> new TrackPoint(null, 1L, "car", LayerId.random(), POSITION, 5.0, Instant.now()));
    }

    @Test
    void rejectsNegativeTrackId() {
        assertThrows(IllegalArgumentException.class,
                () -> new TrackPoint(AssetId.random(), -1L, "car", LayerId.random(), POSITION, 5.0, Instant.now()));
    }

    @Test
    void rejectsBlankLabel() {
        assertThrows(IllegalArgumentException.class,
                () -> new TrackPoint(AssetId.random(), 1L, "  ", LayerId.random(), POSITION, 5.0, Instant.now()));
        assertThrows(IllegalArgumentException.class,
                () -> new TrackPoint(AssetId.random(), 1L, null, LayerId.random(), POSITION, 5.0, Instant.now()));
    }

    @Test
    void rejectsNullLayerId() {
        assertThrows(IllegalArgumentException.class,
                () -> new TrackPoint(AssetId.random(), 1L, "car", null, POSITION, 5.0, Instant.now()));
    }

    @Test
    void rejectsNullPosition() {
        assertThrows(IllegalArgumentException.class,
                () -> new TrackPoint(AssetId.random(), 1L, "car", LayerId.random(), null, 5.0, Instant.now()));
    }

    @Test
    void rejectsNegativeErrorRadius() {
        assertThrows(IllegalArgumentException.class,
                () -> new TrackPoint(AssetId.random(), 1L, "car", LayerId.random(), POSITION, -0.1, Instant.now()));
    }

    @Test
    void rejectsNullCapturedAt() {
        assertThrows(IllegalArgumentException.class,
                () -> new TrackPoint(AssetId.random(), 1L, "car", LayerId.random(), POSITION, 5.0, null));
    }

    @Test
    void acceptsZeroErrorRadius() {
        new TrackPoint(AssetId.random(), 0L, "car", LayerId.random(), POSITION, 0.0, Instant.now());
    }
}
