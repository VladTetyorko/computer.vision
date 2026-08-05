package com.drones.vision.domain.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DrawingTest {

    private static final GeoPosition P1 = new GeoPosition(10, 10, null);
    private static final GeoPosition P2 = new GeoPosition(20, 20, null);
    private static final GeoPosition P3 = new GeoPosition(30, 10, null);

    private static Ownership ownership() {
        return new Ownership(UserId.random(), GroupId.random());
    }

    private static Drawing line() {
        return new Drawing(DrawingId.random(), LayerId.random(), DrawKind.LINE, List.of(P1, P2),
                "route", "accent", ownership(), Instant.now());
    }

    // --- Validation: identity/layer/kind/ownership/createdAt -------------------

    @Test
    void rejectsNullId() {
        assertThrows(IllegalArgumentException.class,
                () -> new Drawing(null, LayerId.random(), DrawKind.LINE, List.of(P1, P2), null, null,
                        ownership(), Instant.now()));
    }

    @Test
    void rejectsNullLayerId() {
        assertThrows(IllegalArgumentException.class,
                () -> new Drawing(DrawingId.random(), null, DrawKind.LINE, List.of(P1, P2), null, null,
                        ownership(), Instant.now()));
    }

    @Test
    void rejectsNullKind() {
        assertThrows(IllegalArgumentException.class,
                () -> new Drawing(DrawingId.random(), LayerId.random(), null, List.of(P1, P2), null, null,
                        ownership(), Instant.now()));
    }

    @Test
    void rejectsNullPoints() {
        assertThrows(IllegalArgumentException.class,
                () -> new Drawing(DrawingId.random(), LayerId.random(), DrawKind.LINE, null, null, null,
                        ownership(), Instant.now()));
    }

    @Test
    void rejectsNullOwnership() {
        assertThrows(IllegalArgumentException.class,
                () -> new Drawing(DrawingId.random(), LayerId.random(), DrawKind.LINE, List.of(P1, P2), null, null,
                        null, Instant.now()));
    }

    @Test
    void rejectsNullCreatedAt() {
        assertThrows(IllegalArgumentException.class,
                () -> new Drawing(DrawingId.random(), LayerId.random(), DrawKind.LINE, List.of(P1, P2), null, null,
                        ownership(), null));
    }

    // --- Geometry invariants -----------------------------------------------------

    @Test
    void lineRequiresAtLeastTwoPoints() {
        assertThrows(IllegalArgumentException.class,
                () -> new Drawing(DrawingId.random(), LayerId.random(), DrawKind.LINE, List.of(), null, null,
                        ownership(), Instant.now()));
        assertThrows(IllegalArgumentException.class,
                () -> new Drawing(DrawingId.random(), LayerId.random(), DrawKind.LINE, List.of(P1), null, null,
                        ownership(), Instant.now()));
    }

    @Test
    void lineAcceptsExactlyTwoPoints() {
        Drawing drawing = new Drawing(DrawingId.random(), LayerId.random(), DrawKind.LINE, List.of(P1, P2), null,
                null, ownership(), Instant.now());

        assertEquals(2, drawing.points().size());
    }

    @Test
    void arrowRequiresAtLeastTwoPoints() {
        assertThrows(IllegalArgumentException.class,
                () -> new Drawing(DrawingId.random(), LayerId.random(), DrawKind.ARROW, List.of(P1), null, null,
                        ownership(), Instant.now()));
    }

    @Test
    void arrowAcceptsExactlyTwoPoints() {
        Drawing drawing = new Drawing(DrawingId.random(), LayerId.random(), DrawKind.ARROW, List.of(P1, P2), null,
                null, ownership(), Instant.now());

        assertEquals(DrawKind.ARROW, drawing.kind());
    }

    @Test
    void polygonRequiresAtLeastThreePoints() {
        assertThrows(IllegalArgumentException.class,
                () -> new Drawing(DrawingId.random(), LayerId.random(), DrawKind.POLYGON, List.of(), null, null,
                        ownership(), Instant.now()));
        assertThrows(IllegalArgumentException.class,
                () -> new Drawing(DrawingId.random(), LayerId.random(), DrawKind.POLYGON, List.of(P1, P2), null,
                        null, ownership(), Instant.now()));
    }

    @Test
    void polygonAcceptsExactlyThreePoints() {
        Drawing drawing = new Drawing(DrawingId.random(), LayerId.random(), DrawKind.POLYGON, List.of(P1, P2, P3),
                null, null, ownership(), Instant.now());

        assertEquals(3, drawing.points().size());
    }

    @Test
    void textRequiresExactlyOnePoint() {
        assertThrows(IllegalArgumentException.class,
                () -> new Drawing(DrawingId.random(), LayerId.random(), DrawKind.TEXT, List.of(), "label", null,
                        ownership(), Instant.now()));
        assertThrows(IllegalArgumentException.class,
                () -> new Drawing(DrawingId.random(), LayerId.random(), DrawKind.TEXT, List.of(P1, P2), "label",
                        null, ownership(), Instant.now()));
    }

    @Test
    void textAcceptsExactlyOnePoint() {
        Drawing drawing = new Drawing(DrawingId.random(), LayerId.random(), DrawKind.TEXT, List.of(P1), "label",
                null, ownership(), Instant.now());

        assertEquals(1, drawing.points().size());
    }

    @Test
    void defensivelyCopiesPoints() {
        List<GeoPosition> mutable = new ArrayList<>(List.of(P1, P2));
        Drawing drawing = new Drawing(DrawingId.random(), LayerId.random(), DrawKind.LINE, mutable, null, null,
                ownership(), Instant.now());

        mutable.add(P3);

        assertEquals(2, drawing.points().size());
        assertThrows(UnsupportedOperationException.class, () -> drawing.points().add(P3));
    }

    // --- Label ---------------------------------------------------------------------

    @Test
    void textRequiresNonBlankLabel() {
        assertThrows(IllegalArgumentException.class,
                () -> new Drawing(DrawingId.random(), LayerId.random(), DrawKind.TEXT, List.of(P1), null, null,
                        ownership(), Instant.now()));
        assertThrows(IllegalArgumentException.class,
                () -> new Drawing(DrawingId.random(), LayerId.random(), DrawKind.TEXT, List.of(P1), "   ", null,
                        ownership(), Instant.now()));
    }

    @Test
    void nonTextAllowsNullLabel() {
        Drawing drawing = new Drawing(DrawingId.random(), LayerId.random(), DrawKind.LINE, List.of(P1, P2), null,
                null, ownership(), Instant.now());

        assertNull(drawing.label());
    }

    @Test
    void nonTextBlankLabelNormalizesToNull() {
        Drawing drawing = new Drawing(DrawingId.random(), LayerId.random(), DrawKind.LINE, List.of(P1, P2), "   ",
                null, ownership(), Instant.now());

        assertNull(drawing.label());
    }

    @Test
    void rejectsLabelLongerThanMax() {
        String tooLong = "a".repeat(Drawing.MAX_LABEL_LENGTH + 1);

        assertThrows(IllegalArgumentException.class,
                () -> new Drawing(DrawingId.random(), LayerId.random(), DrawKind.LINE, List.of(P1, P2), tooLong,
                        null, ownership(), Instant.now()));
    }

    @Test
    void acceptsLabelAtMaxLength() {
        String maxLength = "a".repeat(Drawing.MAX_LABEL_LENGTH);

        Drawing drawing = new Drawing(DrawingId.random(), LayerId.random(), DrawKind.LINE, List.of(P1, P2),
                maxLength, null, ownership(), Instant.now());

        assertEquals(maxLength, drawing.label());
    }

    // --- colorToken ------------------------------------------------------------------

    @Test
    void allowsNullColorToken() {
        Drawing drawing = new Drawing(DrawingId.random(), LayerId.random(), DrawKind.LINE, List.of(P1, P2), null,
                null, ownership(), Instant.now());

        assertNull(drawing.colorToken());
    }

    @Test
    void blankColorTokenNormalizesToNull() {
        Drawing drawing = new Drawing(DrawingId.random(), LayerId.random(), DrawKind.LINE, List.of(P1, P2), null,
                "   ", ownership(), Instant.now());

        assertNull(drawing.colorToken());
    }

    @Test
    void rejectsColorTokenLongerThanMax() {
        String tooLong = "a".repeat(Drawing.MAX_COLOR_TOKEN_LENGTH + 1);

        assertThrows(IllegalArgumentException.class,
                () -> new Drawing(DrawingId.random(), LayerId.random(), DrawKind.LINE, List.of(P1, P2), null,
                        tooLong, ownership(), Instant.now()));
    }

    @Test
    void rejectsColorTokenNotKebabCase() {
        assertThrows(IllegalArgumentException.class,
                () -> new Drawing(DrawingId.random(), LayerId.random(), DrawKind.LINE, List.of(P1, P2), null,
                        "Accent", ownership(), Instant.now()));
        assertThrows(IllegalArgumentException.class,
                () -> new Drawing(DrawingId.random(), LayerId.random(), DrawKind.LINE, List.of(P1, P2), null,
                        "#ff0000", ownership(), Instant.now()));
        assertThrows(IllegalArgumentException.class,
                () -> new Drawing(DrawingId.random(), LayerId.random(), DrawKind.LINE, List.of(P1, P2), null,
                        "danger_zone", ownership(), Instant.now()));
    }

    @Test
    void acceptsAWellFormedColorToken() {
        Drawing drawing = new Drawing(DrawingId.random(), LayerId.random(), DrawKind.LINE, List.of(P1, P2), null,
                "danger-zone", ownership(), Instant.now());

        assertEquals("danger-zone", drawing.colorToken());
    }

    // --- withGeometry -------------------------------------------------------------

    @Test
    void withGeometryReturnsNewInstancePreservingEverythingElse() {
        Drawing original = line();
        List<GeoPosition> newPoints = List.of(P2, P3);

        Drawing updated = original.withGeometry(newPoints);

        assertEquals(newPoints, updated.points());
        assertEquals(original.id(), updated.id());
        assertEquals(original.layerId(), updated.layerId());
        assertEquals(original.kind(), updated.kind());
        assertEquals(original.label(), updated.label());
        assertEquals(original.colorToken(), updated.colorToken());
        assertEquals(original.ownership(), updated.ownership());
        assertEquals(original.createdAt(), updated.createdAt());
        assertEquals(List.of(P1, P2), original.points(), "original instance must be unchanged");
    }

    @Test
    void withGeometryValidatesReplacementPointsAgainstKind() {
        Drawing original = line();

        assertThrows(IllegalArgumentException.class, () -> original.withGeometry(List.of(P1)));
    }

    // --- withDetails -------------------------------------------------------------

    @Test
    void withDetailsReturnsNewInstancePreservingEverythingElse() {
        Drawing original = line();

        Drawing updated = original.withDetails("renamed", "danger");

        assertEquals("renamed", updated.label());
        assertEquals("danger", updated.colorToken());
        assertEquals(original.id(), updated.id());
        assertEquals(original.layerId(), updated.layerId());
        assertEquals(original.kind(), updated.kind());
        assertEquals(original.points(), updated.points());
        assertEquals(original.ownership(), updated.ownership());
        assertEquals(original.createdAt(), updated.createdAt());
        assertEquals("route", original.label(), "original instance must be unchanged");
    }

    @Test
    void withDetailsValidatesReplacementFields() {
        Drawing original = line();

        assertThrows(IllegalArgumentException.class, () -> original.withDetails("a".repeat(200), "accent"));
        assertThrows(IllegalArgumentException.class, () -> original.withDetails("route", "Not Kebab"));
    }
}
