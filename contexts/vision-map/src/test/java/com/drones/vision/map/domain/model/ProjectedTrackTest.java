package com.drones.vision.map.domain.model;

import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.GeoPosition;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertThrows;

class ProjectedTrackTest {

    private static final GeoPosition POSITION = new GeoPosition(50.45, 30.52, null);

    @Test
    void rejectsNullAssetId() {
        assertThrows(IllegalArgumentException.class,
                () -> new ProjectedTrack(null, 1L, "car", LayerId.random(), POSITION, 50.0, 5.0, Instant.now()));
    }

    @Test
    void rejectsNegativeTrackId() {
        assertThrows(IllegalArgumentException.class,
                () -> new ProjectedTrack(AssetId.random(), -1L, "car", LayerId.random(), POSITION, 50.0, 5.0,
                        Instant.now()));
    }

    @Test
    void rejectsBlankLabel() {
        assertThrows(IllegalArgumentException.class,
                () -> new ProjectedTrack(AssetId.random(), 1L, "", LayerId.random(), POSITION, 50.0, 5.0,
                        Instant.now()));
    }

    @Test
    void rejectsNullLayerId() {
        assertThrows(IllegalArgumentException.class,
                () -> new ProjectedTrack(AssetId.random(), 1L, "car", null, POSITION, 50.0, 5.0, Instant.now()));
    }

    @Test
    void rejectsNullPosition() {
        assertThrows(IllegalArgumentException.class,
                () -> new ProjectedTrack(AssetId.random(), 1L, "car", LayerId.random(), null, 50.0, 5.0,
                        Instant.now()));
    }

    @Test
    void rejectsNegativeRangeMeters() {
        assertThrows(IllegalArgumentException.class,
                () -> new ProjectedTrack(AssetId.random(), 1L, "car", LayerId.random(), POSITION, -1.0, 5.0,
                        Instant.now()));
    }

    @Test
    void rejectsNegativeErrorRadiusMeters() {
        assertThrows(IllegalArgumentException.class,
                () -> new ProjectedTrack(AssetId.random(), 1L, "car", LayerId.random(), POSITION, 50.0, -1.0,
                        Instant.now()));
    }

    @Test
    void rejectsNullUpdatedAt() {
        assertThrows(IllegalArgumentException.class,
                () -> new ProjectedTrack(AssetId.random(), 1L, "car", LayerId.random(), POSITION, 50.0, 5.0, null));
    }

    @Test
    void acceptsZeroRangeAndErrorRadius() {
        new ProjectedTrack(AssetId.random(), 0L, "car", LayerId.random(), POSITION, 0.0, 0.0, Instant.now());
    }
}
