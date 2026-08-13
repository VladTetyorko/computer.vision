package com.drones.vision.map.domain.model;

import com.drones.vision.kernel.GroupId;
import com.drones.vision.kernel.Ownership;
import com.drones.vision.kernel.UserId;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MapLayerTest {

    private static Ownership ownership() {
        return new Ownership(UserId.random(), GroupId.random());
    }

    private static LayerGrant grant() {
        return new LayerGrant(LayerGrant.SubjectType.USER, UUID.randomUUID(), AccessLevel.VIEW);
    }

    private static MapLayer layer() {
        return new MapLayer(LayerId.random(), "Team Alpha", LayerKind.TEAM, ownership(),
                List.of(grant()), Instant.now());
    }

    // --- Validation ------------------------------------------------------------

    @Test
    void rejectsNullId() {
        assertThrows(IllegalArgumentException.class,
                () -> new MapLayer(null, "name", LayerKind.TEAM, ownership(), List.of(), Instant.now()));
    }

    @Test
    void rejectsBlankName() {
        assertThrows(IllegalArgumentException.class,
                () -> new MapLayer(LayerId.random(), "", LayerKind.TEAM, ownership(), List.of(), Instant.now()));
        assertThrows(IllegalArgumentException.class,
                () -> new MapLayer(LayerId.random(), "   ", LayerKind.TEAM, ownership(), List.of(), Instant.now()));
        assertThrows(IllegalArgumentException.class,
                () -> new MapLayer(LayerId.random(), null, LayerKind.TEAM, ownership(), List.of(), Instant.now()));
    }

    @Test
    void rejectsNameLongerThanMax() {
        String tooLong = "a".repeat(MapLayer.MAX_NAME_LENGTH + 1);

        assertThrows(IllegalArgumentException.class,
                () -> new MapLayer(LayerId.random(), tooLong, LayerKind.TEAM, ownership(), List.of(), Instant.now()));
    }

    @Test
    void acceptsNameAtMaxLength() {
        String maxLength = "a".repeat(MapLayer.MAX_NAME_LENGTH);

        MapLayer layer = new MapLayer(LayerId.random(), maxLength, LayerKind.TEAM, ownership(), List.of(), Instant.now());

        assertEquals(maxLength, layer.name());
    }

    @Test
    void rejectsNullKind() {
        assertThrows(IllegalArgumentException.class,
                () -> new MapLayer(LayerId.random(), "name", null, ownership(), List.of(), Instant.now()));
    }

    @Test
    void rejectsNullOwnership() {
        assertThrows(IllegalArgumentException.class,
                () -> new MapLayer(LayerId.random(), "name", LayerKind.TEAM, null, List.of(), Instant.now()));
    }

    @Test
    void rejectsNullGrants() {
        assertThrows(IllegalArgumentException.class,
                () -> new MapLayer(LayerId.random(), "name", LayerKind.TEAM, ownership(), null, Instant.now()));
    }

    @Test
    void rejectsNullCreatedAt() {
        assertThrows(IllegalArgumentException.class,
                () -> new MapLayer(LayerId.random(), "name", LayerKind.TEAM, ownership(), List.of(), null));
    }

    @Test
    void acceptsEmptyGrants() {
        MapLayer layer = new MapLayer(LayerId.random(), "name", LayerKind.COP, ownership(), List.of(), Instant.now());

        assertTrue(layer.grants().isEmpty());
    }

    @Test
    void defensivelyCopiesGrants() {
        List<LayerGrant> mutable = new ArrayList<>();
        mutable.add(grant());
        MapLayer layer = new MapLayer(LayerId.random(), "name", LayerKind.TEAM, ownership(), mutable, Instant.now());

        mutable.add(grant());

        assertEquals(1, layer.grants().size());
        assertThrows(UnsupportedOperationException.class, () -> layer.grants().add(grant()));
    }

    // --- withName ------------------------------------------------------------

    @Test
    void withNameReturnsNewInstancePreservingEverythingElse() {
        MapLayer original = layer();

        MapLayer renamed = original.withName("Team Bravo");

        assertEquals("Team Bravo", renamed.name());
        assertEquals(original.id(), renamed.id());
        assertEquals(original.kind(), renamed.kind());
        assertEquals(original.ownership(), renamed.ownership());
        assertEquals(original.grants(), renamed.grants());
        assertEquals(original.createdAt(), renamed.createdAt());
        assertEquals("Team Alpha", original.name(), "original instance must be unchanged");
    }

    @Test
    void withNameValidatesReplacementName() {
        MapLayer original = layer();

        assertThrows(IllegalArgumentException.class, () -> original.withName(""));
        assertThrows(IllegalArgumentException.class, () -> original.withName(null));
    }

    // --- withGrants ------------------------------------------------------------

    @Test
    void withGrantsReturnsNewInstancePreservingEverythingElse() {
        MapLayer original = layer();
        List<LayerGrant> newGrants = List.of(grant(), grant());

        MapLayer updated = original.withGrants(newGrants);

        assertEquals(2, updated.grants().size());
        assertEquals(original.id(), updated.id());
        assertEquals(original.name(), updated.name());
        assertEquals(original.kind(), updated.kind());
        assertEquals(original.ownership(), updated.ownership());
        assertEquals(original.createdAt(), updated.createdAt());
        assertEquals(1, original.grants().size(), "original instance must be unchanged");
    }

    @Test
    void withGrantsRejectsNull() {
        MapLayer original = layer();

        assertThrows(IllegalArgumentException.class, () -> original.withGrants(null));
    }
}
