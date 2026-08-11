package com.drones.vision.learning.domain.model;

import com.drones.vision.kernel.CategoryId;
import com.drones.vision.kernel.GroupId;
import com.drones.vision.kernel.Ownership;
import com.drones.vision.kernel.UserId;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DatasetTest {

    private static Ownership ownership() {
        return new Ownership(UserId.random(), GroupId.random());
    }

    private static Dataset dataset(List<String> classes) {
        return new Dataset(DatasetId.random(), "Buildings", new CategoryId("building"), classes,
                ownership(), DatasetStatus.OPEN, Instant.now());
    }

    @Test
    void rejectsNullId() {
        assertThrows(IllegalArgumentException.class,
                () -> new Dataset(null, "name", null, List.of(), ownership(), DatasetStatus.OPEN, Instant.now()));
    }

    @Test
    void rejectsBlankName() {
        assertThrows(IllegalArgumentException.class,
                () -> new Dataset(DatasetId.random(), "", null, List.of(), ownership(), DatasetStatus.OPEN,
                        Instant.now()));
        assertThrows(IllegalArgumentException.class,
                () -> new Dataset(DatasetId.random(), null, null, List.of(), ownership(), DatasetStatus.OPEN,
                        Instant.now()));
        assertThrows(IllegalArgumentException.class,
                () -> new Dataset(DatasetId.random(), "   ", null, List.of(), ownership(), DatasetStatus.OPEN,
                        Instant.now()));
    }

    @Test
    void acceptsNullTargetCategory() {
        Dataset dataset = new Dataset(DatasetId.random(), "name", null, List.of("building"), ownership(),
                DatasetStatus.OPEN, Instant.now());

        assertNull(dataset.targetCategory());
    }

    @Test
    void rejectsNullClasses() {
        assertThrows(IllegalArgumentException.class,
                () -> new Dataset(DatasetId.random(), "name", null, null, ownership(), DatasetStatus.OPEN,
                        Instant.now()));
    }

    @Test
    void allowsEmptyClasses() {
        Dataset dataset = dataset(List.of());

        assertEquals(0, dataset.classes().size());
    }

    @Test
    void rejectsNullOwnership() {
        assertThrows(IllegalArgumentException.class,
                () -> new Dataset(DatasetId.random(), "name", null, List.of(), null, DatasetStatus.OPEN,
                        Instant.now()));
    }

    @Test
    void rejectsNullStatus() {
        assertThrows(IllegalArgumentException.class,
                () -> new Dataset(DatasetId.random(), "name", null, List.of(), ownership(), null, Instant.now()));
    }

    @Test
    void rejectsNullCreatedAt() {
        assertThrows(IllegalArgumentException.class,
                () -> new Dataset(DatasetId.random(), "name", null, List.of(), ownership(), DatasetStatus.OPEN,
                        null));
    }

    @Test
    void classesListIsDefensivelyCopiedAndOrderPreserving() {
        List<String> classes = new ArrayList<>(List.of("building", "tower"));

        Dataset dataset = dataset(classes);
        classes.add("mutation");

        assertEquals(List.of("building", "tower"), dataset.classes(),
                "later mutation of the source list must not affect the dataset");
        assertThrows(UnsupportedOperationException.class, () -> dataset.classes().add("nope"),
                "returned classes list must be immutable");
    }
}
