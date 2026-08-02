package com.drones.vision.application.training;

import com.drones.vision.domain.model.Annotation;
import com.drones.vision.domain.model.AnnotationSource;
import com.drones.vision.domain.model.AssetId;
import com.drones.vision.domain.model.BoundingBox;
import com.drones.vision.domain.model.DatasetId;
import com.drones.vision.domain.model.SampleImage;
import com.drones.vision.domain.model.SampleStatus;
import com.drones.vision.domain.model.StreamId;
import com.drones.vision.domain.model.TrainingSample;
import com.drones.vision.domain.model.TrainingSampleId;
import com.drones.vision.domain.port.out.DatasetUploadPort;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class YoloDatasetWriterTest {

    private static TrainingSample sample(List<Annotation> annotations) {
        return new TrainingSample(TrainingSampleId.random(), DatasetId.random(), StreamId.random(),
                AssetId.random(), Instant.now(), 640, 480, annotations, SampleStatus.LABELED, null, null);
    }

    @Test
    void toEntryUsesTheSampleIdAsTheImageBasename() {
        TrainingSample sample = sample(List.of());
        SampleImage image = new SampleImage(new byte[]{1, 2, 3}, "image/jpeg");

        DatasetUploadPort.ExportEntry entry = YoloDatasetWriter.toEntry(sample, image, List.of("building"));

        assertEquals(sample.id().value() + ".jpg", entry.imageName());
        assertEquals(3, entry.imageBytes().length);
    }

    @Test
    void toEntryProducesAnEmptyLabelFileForAZeroAnnotationSample() {
        TrainingSample sample = sample(List.of());
        SampleImage image = new SampleImage(new byte[]{1}, "image/jpeg");

        DatasetUploadPort.ExportEntry entry = YoloDatasetWriter.toEntry(sample, image, List.of("building"));

        assertEquals("", entry.labelFileText());
    }

    @Test
    void convertsTopLeftBoxToYoloCenterFormatWithTheCorrectClassIndex() {
        Annotation annotation = new Annotation("tower", new BoundingBox(0.10, 0.20, 0.30, 0.40),
                AnnotationSource.OPERATOR);
        TrainingSample sample = sample(List.of(annotation));
        SampleImage image = new SampleImage(new byte[]{1}, "image/jpeg");

        DatasetUploadPort.ExportEntry entry =
                YoloDatasetWriter.toEntry(sample, image, List.of("building", "tower"));

        // class index 1 ("tower"), cx = 0.10 + 0.30/2 = 0.25, cy = 0.20 + 0.40/2 = 0.40
        assertEquals("1 0.250000 0.400000 0.300000 0.400000\n", entry.labelFileText());
    }

    @Test
    void writesOneLinePerAnnotationInOrder() {
        Annotation first = new Annotation("building", new BoundingBox(0, 0, 0.1, 0.1), AnnotationSource.OPERATOR);
        Annotation second = new Annotation("tower", new BoundingBox(0.5, 0.5, 0.2, 0.2), AnnotationSource.OPERATOR);
        TrainingSample sample = sample(List.of(first, second));
        SampleImage image = new SampleImage(new byte[]{1}, "image/jpeg");

        DatasetUploadPort.ExportEntry entry =
                YoloDatasetWriter.toEntry(sample, image, List.of("building", "tower"));

        String[] lines = entry.labelFileText().split("\n");
        assertEquals(2, lines.length);
        assertEquals("0 0.050000 0.050000 0.100000 0.100000", lines[0]);
        assertEquals("1 0.600000 0.600000 0.200000 0.200000", lines[1]);
    }

    @Test
    void throwsIllegalStateWhenALabelIsNotAMemberOfClasses() {
        Annotation annotation = new Annotation("tank", new BoundingBox(0, 0, 0.1, 0.1), AnnotationSource.OPERATOR);
        TrainingSample sample = sample(List.of(annotation));
        SampleImage image = new SampleImage(new byte[]{1}, "image/jpeg");

        assertThrows(IllegalStateException.class,
                () -> YoloDatasetWriter.toEntry(sample, image, List.of("building")));
    }

    // --- dataYaml ----------------------------------------------------------------

    @Test
    void dataYamlListsClassNamesCountAndTheFixedImagesPaths() {
        String yaml = YoloDatasetWriter.dataYaml(List.of("building", "tower"));

        assertEquals("names: [building, tower]\nnc: 2\ntrain: images\nval: images\n", yaml);
    }

    @Test
    void dataYamlHandlesAnEmptyClassList() {
        String yaml = YoloDatasetWriter.dataYaml(List.of());

        assertEquals("names: []\nnc: 0\ntrain: images\nval: images\n", yaml);
    }
}
