package com.drones.vision.map.application;

import com.drones.vision.map.domain.model.DrawKind;
import com.drones.vision.kernel.GeoPosition;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DrawingSpecTest {

    private static GeoPosition p(double v) {
        return new GeoPosition(v, v, null);
    }

    @Test
    void rejectsNullKind() {
        assertThrows(IllegalArgumentException.class, () -> new DrawingSpec(null, null, List.of(p(1)), null, null));
    }

    @Test
    void rejectsNullPoints() {
        assertThrows(IllegalArgumentException.class, () -> new DrawingSpec(null, DrawKind.LINE, null, null, null));
    }

    @Test
    void lineRequiresAtLeastTwoPoints() {
        assertThrows(IllegalArgumentException.class,
                () -> new DrawingSpec(null, DrawKind.LINE, List.of(p(1)), null, null));
        new DrawingSpec(null, DrawKind.LINE, List.of(p(1), p(2)), null, null);
    }

    @Test
    void polygonRequiresAtLeastThreePoints() {
        assertThrows(IllegalArgumentException.class,
                () -> new DrawingSpec(null, DrawKind.POLYGON, List.of(p(1), p(2)), null, null));
        new DrawingSpec(null, DrawKind.POLYGON, List.of(p(1), p(2), p(3)), null, null);
    }

    @Test
    void textRequiresExactlyOnePoint() {
        assertThrows(IllegalArgumentException.class,
                () -> new DrawingSpec(null, DrawKind.TEXT, List.of(p(1), p(2)), "label", null));
        new DrawingSpec(null, DrawKind.TEXT, List.of(p(1)), "label", null);
    }

    @Test
    void textRequiresANonBlankLabel() {
        assertThrows(IllegalArgumentException.class,
                () -> new DrawingSpec(null, DrawKind.TEXT, List.of(p(1)), null, null));
        assertThrows(IllegalArgumentException.class,
                () -> new DrawingSpec(null, DrawKind.TEXT, List.of(p(1)), "  ", null));
    }

    @Test
    void nonTextBlankLabelNormalizesToNull() {
        DrawingSpec spec = new DrawingSpec(null, DrawKind.LINE, List.of(p(1), p(2)), "  ", null);
        assertNull(spec.label());
    }

    @Test
    void blankColorTokenNormalizesToNull() {
        DrawingSpec spec = new DrawingSpec(null, DrawKind.LINE, List.of(p(1), p(2)), null, "  ");
        assertNull(spec.colorToken());
    }

    @Test
    void pointsAreDefensivelyCopied() {
        List<GeoPosition> mutable = new java.util.ArrayList<>(List.of(p(1), p(2)));
        DrawingSpec spec = new DrawingSpec(null, DrawKind.LINE, mutable, null, null);
        mutable.add(p(3));
        assertEquals(2, spec.points().size());
    }
}
