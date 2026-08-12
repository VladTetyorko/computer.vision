package com.drones.vision.learning.domain.model;

import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.BoundingBox;
import com.drones.vision.kernel.StreamId;
import com.drones.vision.kernel.UserId;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TrainingSampleTest {

    private static Annotation annotation() {
        return new Annotation("building", new BoundingBox(0.1, 0.2, 0.3, 0.25), AnnotationSource.MODEL);
    }

    private static TrainingSample sample(List<Annotation> annotations) {
        return new TrainingSample(TrainingSampleId.random(), DatasetId.random(), StreamId.random(),
                AssetId.random(), Instant.now(), 1920, 1080, annotations, SampleStatus.PENDING,
                UserId.random(), Instant.now());
    }

    @Test
    void rejectsNullId() {
        assertThrows(IllegalArgumentException.class,
                () -> new TrainingSample(null, DatasetId.random(), StreamId.random(), AssetId.random(),
                        Instant.now(), 1920, 1080, List.of(), SampleStatus.PENDING, null, null));
    }

    @Test
    void rejectsNullDatasetId() {
        assertThrows(IllegalArgumentException.class,
                () -> new TrainingSample(TrainingSampleId.random(), null, StreamId.random(), AssetId.random(),
                        Instant.now(), 1920, 1080, List.of(), SampleStatus.PENDING, null, null));
    }

    @Test
    void rejectsNullStreamId() {
        assertThrows(IllegalArgumentException.class,
                () -> new TrainingSample(TrainingSampleId.random(), DatasetId.random(), null, AssetId.random(),
                        Instant.now(), 1920, 1080, List.of(), SampleStatus.PENDING, null, null));
    }

    @Test
    void acceptsNullAssetId() {
        TrainingSample sample = new TrainingSample(TrainingSampleId.random(), DatasetId.random(), StreamId.random(),
                null, Instant.now(), 1920, 1080, List.of(), SampleStatus.PENDING, null, null);

        assertNull(sample.assetId());
    }

    @Test
    void rejectsNullCapturedAt() {
        assertThrows(IllegalArgumentException.class,
                () -> new TrainingSample(TrainingSampleId.random(), DatasetId.random(), StreamId.random(),
                        AssetId.random(), null, 1920, 1080, List.of(), SampleStatus.PENDING, null, null));
    }

    @Test
    void rejectsNonPositiveWidth() {
        assertThrows(IllegalArgumentException.class,
                () -> new TrainingSample(TrainingSampleId.random(), DatasetId.random(), StreamId.random(),
                        AssetId.random(), Instant.now(), 0, 1080, List.of(), SampleStatus.PENDING, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> new TrainingSample(TrainingSampleId.random(), DatasetId.random(), StreamId.random(),
                        AssetId.random(), Instant.now(), -1, 1080, List.of(), SampleStatus.PENDING, null, null));
    }

    @Test
    void rejectsNonPositiveHeight() {
        assertThrows(IllegalArgumentException.class,
                () -> new TrainingSample(TrainingSampleId.random(), DatasetId.random(), StreamId.random(),
                        AssetId.random(), Instant.now(), 1920, 0, List.of(), SampleStatus.PENDING, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> new TrainingSample(TrainingSampleId.random(), DatasetId.random(), StreamId.random(),
                        AssetId.random(), Instant.now(), 1920, -1, List.of(), SampleStatus.PENDING, null, null));
    }

    @Test
    void rejectsNullAnnotations() {
        assertThrows(IllegalArgumentException.class,
                () -> new TrainingSample(TrainingSampleId.random(), DatasetId.random(), StreamId.random(),
                        AssetId.random(), Instant.now(), 1920, 1080, null, SampleStatus.PENDING, null, null));
    }

    @Test
    void allowsEmptyAnnotations() {
        TrainingSample sample = sample(List.of());

        assertEquals(0, sample.annotations().size());
    }

    @Test
    void rejectsNullStatus() {
        assertThrows(IllegalArgumentException.class,
                () -> new TrainingSample(TrainingSampleId.random(), DatasetId.random(), StreamId.random(),
                        AssetId.random(), Instant.now(), 1920, 1080, List.of(), null, null, null));
    }

    @Test
    void acceptsNullLabeledByAndLabeledAt() {
        TrainingSample sample = new TrainingSample(TrainingSampleId.random(), DatasetId.random(), StreamId.random(),
                AssetId.random(), Instant.now(), 1920, 1080, List.of(), SampleStatus.PENDING, null, null);

        assertNull(sample.labeledBy());
        assertNull(sample.labeledAt());
    }

    @Test
    void annotationsListIsDefensivelyCopied() {
        List<Annotation> annotations = new ArrayList<>();
        annotations.add(annotation());

        TrainingSample sample = sample(annotations);
        annotations.add(annotation());

        assertEquals(1, sample.annotations().size(), "later mutation of the source list must not affect the sample");
        assertThrows(UnsupportedOperationException.class, () -> sample.annotations().add(annotation()),
                "returned annotations list must be immutable");
    }
}
