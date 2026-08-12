package com.drones.vision.learning.domain.model;

import com.drones.vision.kernel.BoundingBox;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AnnotationTest {

    private static final BoundingBox BOX = new BoundingBox(0.1, 0.2, 0.3, 0.4);

    @Test
    void rejectsNullLabel() {
        assertThrows(IllegalArgumentException.class,
                () -> new Annotation(null, BOX, AnnotationSource.OPERATOR));
    }

    @Test
    void rejectsBlankLabel() {
        assertThrows(IllegalArgumentException.class,
                () -> new Annotation("", BOX, AnnotationSource.OPERATOR));
        assertThrows(IllegalArgumentException.class,
                () -> new Annotation("   ", BOX, AnnotationSource.OPERATOR));
    }

    @Test
    void rejectsNullBox() {
        assertThrows(IllegalArgumentException.class,
                () -> new Annotation("building", null, AnnotationSource.OPERATOR));
    }

    @Test
    void rejectsNullSource() {
        assertThrows(IllegalArgumentException.class,
                () -> new Annotation("building", BOX, null));
    }

    @Test
    void acceptsValidAnnotation() {
        Annotation annotation = new Annotation("building", BOX, AnnotationSource.MODEL);

        assertEquals("building", annotation.label());
        assertEquals(BOX, annotation.box());
        assertEquals(AnnotationSource.MODEL, annotation.source());
    }
}
