package com.drones.vision.application.training;

import com.drones.vision.learning.domain.model.Annotation;
import com.drones.vision.learning.domain.model.AnnotationSource;
import com.drones.vision.kernel.BoundingBox;
import com.drones.vision.learning.domain.model.SampleStatus;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LabelSpecTest {

    private static Annotation annotation() {
        return new Annotation("building", new BoundingBox(0.1, 0.1, 0.2, 0.2), AnnotationSource.OPERATOR);
    }

    @Test
    void rejectsNullAnnotations() {
        assertThrows(NullPointerException.class, () -> new LabelSpec(null, SampleStatus.LABELED));
    }

    @Test
    void rejectsNullStatus() {
        assertThrows(NullPointerException.class, () -> new LabelSpec(List.of(annotation()), null));
    }

    @Test
    void rejectsPendingAsATargetStatus() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new LabelSpec(List.of(annotation()), SampleStatus.PENDING));
        assertEquals(true, ex.getMessage().contains("PENDING"));
    }

    @Test
    void acceptsLabeledAndDiscarded() {
        assertEquals(SampleStatus.LABELED, new LabelSpec(List.of(annotation()), SampleStatus.LABELED).status());
        assertEquals(SampleStatus.DISCARDED, new LabelSpec(List.of(), SampleStatus.DISCARDED).status());
    }

    @Test
    void annotationsAreDefensivelyCopied() {
        List<Annotation> mutable = new ArrayList<>(List.of(annotation()));

        LabelSpec spec = new LabelSpec(mutable, SampleStatus.LABELED);
        mutable.clear();

        assertEquals(1, spec.annotations().size(), "later mutation of the source list must not affect the spec");
        assertThrows(UnsupportedOperationException.class, () -> spec.annotations().add(annotation()),
                "returned annotations list must be immutable");
    }
}
