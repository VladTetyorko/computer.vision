package com.drones.vision.perception.domain.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TileCoordinateTest {

    @Test
    void idIsZSlashXSlashY() {
        assertEquals("17/76687/44230", new TileCoordinate(17, 76687, 44230).id());
    }

    @Test
    void rejectsNegativeZoom() {
        assertThrows(IllegalArgumentException.class, () -> new TileCoordinate(-1, 0, 0));
    }

    @Test
    void rejectsXOrYOutsideTheZoomsSpan() {
        // zoom 1 spans x,y in [0,2)
        assertThrows(IllegalArgumentException.class, () -> new TileCoordinate(1, 2, 0));
        assertThrows(IllegalArgumentException.class, () -> new TileCoordinate(1, 0, 2));
        assertThrows(IllegalArgumentException.class, () -> new TileCoordinate(1, -1, 0));
    }
}
