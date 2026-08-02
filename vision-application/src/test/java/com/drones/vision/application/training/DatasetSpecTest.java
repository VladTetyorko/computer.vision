package com.drones.vision.application.training;

import com.drones.vision.domain.model.CategoryId;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DatasetSpecTest {

    @Test
    void rejectsBlankName() {
        assertThrows(IllegalArgumentException.class, () -> new DatasetSpec(" ", null, List.of("building")));
        assertThrows(IllegalArgumentException.class, () -> new DatasetSpec(null, null, List.of("building")));
    }

    @Test
    void rejectsNullClasses() {
        assertThrows(IllegalArgumentException.class, () -> new DatasetSpec("Buildings", null, null));
    }

    @Test
    void acceptsNullTargetCategoryAndEmptyClasses() {
        DatasetSpec spec = new DatasetSpec("Buildings", null, List.of());

        assertNull(spec.targetCategory());
        assertEquals(List.of(), spec.classes());
    }

    @Test
    void classesAreDefensivelyCopiedAndOrderPreserved() {
        List<String> mutable = new ArrayList<>(List.of("building", "tower"));

        DatasetSpec spec = new DatasetSpec("Buildings", new CategoryId("building"), mutable);
        mutable.add("bridge");

        assertEquals(List.of("building", "tower"), spec.classes(),
                "later mutation of the source list must not affect the spec");
        assertThrows(UnsupportedOperationException.class, () -> spec.classes().add("bridge"),
                "returned classes list must be immutable");
    }
}
