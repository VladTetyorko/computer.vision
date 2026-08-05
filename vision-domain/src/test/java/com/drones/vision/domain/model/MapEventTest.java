package com.drones.vision.domain.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MapEventTest {

    private static Ownership ownership() {
        return new Ownership(UserId.random(), GroupId.random());
    }

    private static Mark mark(LayerId layerId) {
        return new Mark(MarkId.random(), layerId, new GeoPosition(1, 1, null), MarkKind.TARGET,
                Affiliation.HOSTILE, "label", null, ownership(), Instant.now(), MarkStatus.ACTIVE,
                MarkSource.MANUAL, Verification.unverified());
    }

    private static Drawing drawing(LayerId layerId) {
        return new Drawing(DrawingId.random(), layerId, DrawKind.LINE,
                List.of(new GeoPosition(1, 1, null), new GeoPosition(2, 2, null)), null, null,
                ownership(), Instant.now());
    }

    private static MapLayer layer(LayerId id) {
        return new MapLayer(id, "name", LayerKind.TEAM, ownership(), List.of(), Instant.now());
    }

    // --- Validation ------------------------------------------------------------

    @Test
    void rejectsNullEntity() {
        LayerId layerId = LayerId.random();
        assertThrows(IllegalArgumentException.class,
                () -> new MapEvent(null, MapEvent.Action.CREATED, layerId, mark(layerId)));
    }

    @Test
    void rejectsNullAction() {
        LayerId layerId = LayerId.random();
        assertThrows(IllegalArgumentException.class,
                () -> new MapEvent(MapEvent.EntityType.MARK, null, layerId, mark(layerId)));
    }

    @Test
    void rejectsNullLayerId() {
        LayerId layerId = LayerId.random();
        assertThrows(IllegalArgumentException.class,
                () -> new MapEvent(MapEvent.EntityType.MARK, MapEvent.Action.CREATED, null, mark(layerId)));
    }

    @Test
    void rejectsNullPayload() {
        LayerId layerId = LayerId.random();
        assertThrows(IllegalArgumentException.class,
                () -> new MapEvent(MapEvent.EntityType.MARK, MapEvent.Action.CREATED, layerId, null));
    }

    // --- payload type must match entity -----------------------------------------

    @Test
    void acceptsMarkPayloadForMarkEntity() {
        LayerId layerId = LayerId.random();
        Mark mark = mark(layerId);

        MapEvent event = new MapEvent(MapEvent.EntityType.MARK, MapEvent.Action.CREATED, layerId, mark);

        assertEquals(mark, event.payload());
    }

    @Test
    void acceptsDrawingPayloadForDrawingEntity() {
        LayerId layerId = LayerId.random();
        Drawing drawing = drawing(layerId);

        MapEvent event = new MapEvent(MapEvent.EntityType.DRAWING, MapEvent.Action.CREATED, layerId, drawing);

        assertEquals(drawing, event.payload());
    }

    @Test
    void acceptsLayerPayloadForLayerEntity() {
        LayerId layerId = LayerId.random();
        MapLayer layer = layer(layerId);

        MapEvent event = new MapEvent(MapEvent.EntityType.LAYER, MapEvent.Action.CREATED, layerId, layer);

        assertEquals(layer, event.payload());
    }

    @Test
    void rejectsMismatchedPayloadType() {
        LayerId layerId = LayerId.random();

        assertThrows(IllegalArgumentException.class,
                () -> new MapEvent(MapEvent.EntityType.MARK, MapEvent.Action.CREATED, layerId, drawing(layerId)));
        assertThrows(IllegalArgumentException.class,
                () -> new MapEvent(MapEvent.EntityType.DRAWING, MapEvent.Action.CREATED, layerId, mark(layerId)));
        assertThrows(IllegalArgumentException.class,
                () -> new MapEvent(MapEvent.EntityType.LAYER, MapEvent.Action.CREATED, layerId, mark(layerId)));
    }

    // --- CLEARED is mark-only -----------------------------------------------------

    @Test
    void clearedIsValidForMarkEntity() {
        LayerId layerId = LayerId.random();
        Mark mark = mark(layerId);

        MapEvent event = new MapEvent(MapEvent.EntityType.MARK, MapEvent.Action.CLEARED, layerId, mark);

        assertEquals(MapEvent.Action.CLEARED, event.action());
    }

    @Test
    void rejectsClearedForDrawingEntity() {
        LayerId layerId = LayerId.random();

        assertThrows(IllegalArgumentException.class,
                () -> new MapEvent(MapEvent.EntityType.DRAWING, MapEvent.Action.CLEARED, layerId, drawing(layerId)));
    }

    @Test
    void rejectsClearedForLayerEntity() {
        LayerId layerId = LayerId.random();

        assertThrows(IllegalArgumentException.class,
                () -> new MapEvent(MapEvent.EntityType.LAYER, MapEvent.Action.CLEARED, layerId, layer(layerId)));
    }
}
