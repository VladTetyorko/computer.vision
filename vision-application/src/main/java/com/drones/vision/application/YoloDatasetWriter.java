package com.drones.vision.application;

import com.drones.vision.domain.model.Annotation;
import com.drones.vision.domain.model.BoundingBox;
import com.drones.vision.domain.model.SampleImage;
import com.drones.vision.domain.model.TrainingSample;
import com.drones.vision.domain.port.out.DatasetExportPort;

import java.util.List;
import java.util.Locale;

/**
 * Pure, in-memory composition of one {@link TrainingSample}'s YOLO export entry
 * (docs/CV-TRAINING-PLAN.md §5) — no I/O, fully unit-testable; {@link
 * com.drones.vision.domain.port.out.DatasetExportPort} only sinks the bytes this class produces.
 *
 * <h2>Box conversion (frozen, docs/CV-TRAINING-PLAN.md §5)</h2>
 * The platform's {@link BoundingBox} is top-left-origin, normalized {@code [0,1]}
 * {@code (x, y, width, height)}; YOLO label lines are center-based: {@code cx = x + width/2},
 * {@code cy = y + height/2}, {@code w = width}, {@code h = height}, all still normalized
 * {@code [0,1]}. The class index is the annotation's label's position in the dataset's {@code
 * classes} list.
 *
 * <p>Package-private, stateless, no interface — one call site ({@link
 * DefaultLabelingService#export}), no second implementation ever plausible (java-clean-code
 * SKILL.md §1).
 */
final class YoloDatasetWriter {

    private static final String IMAGE_EXTENSION = ".jpg";

    private YoloDatasetWriter() {
    }

    /**
     * Builds the {@link com.drones.vision.domain.port.out.DatasetExportPort.ExportEntry} for one
     * {@link com.drones.vision.domain.model.SampleStatus#LABELED} sample.
     *
     * @param sample  the labeled sample being exported
     * @param image   the sample's stored image bytes
     * @param classes the dataset's ordered YOLO class vocabulary; every {@code sample}
     *                annotation's label must be a member (guaranteed by {@link
     *                DefaultLabelingService#label}'s own membership check at label time — a
     *                missing label here means that invariant was somehow violated)
     * @return the entry ready to hand to {@link
     *         com.drones.vision.domain.port.out.DatasetExportPort#write}
     * @throws IllegalStateException if an annotation's label is not in {@code classes}
     */
    static DatasetExportPort.ExportEntry toEntry(TrainingSample sample, SampleImage image, List<String> classes) {
        String imageName = sample.id().value() + IMAGE_EXTENSION;
        return new DatasetExportPort.ExportEntry(imageName, image.data(), labelText(sample.annotations(), classes));
    }

    /**
     * Builds the YOLO label file text for one sample's annotations — one {@code <class_index> <cx>
     * <cy> <w> <h>} line per annotation, or empty text for a sample with zero annotations (a valid
     * YOLO "negative"/background image, docs/CV-TRAINING-PLAN.md §5).
     */
    private static String labelText(List<Annotation> annotations, List<String> classes) {
        StringBuilder text = new StringBuilder();
        for (Annotation annotation : annotations) {
            int classIndex = classes.indexOf(annotation.label());
            if (classIndex < 0) {
                throw new IllegalStateException("Annotation label '" + annotation.label()
                        + "' is not a member of dataset classes " + classes);
            }
            BoundingBox box = annotation.box();
            double centerX = box.x() + box.width() / 2.0;
            double centerY = box.y() + box.height() / 2.0;
            text.append(classIndex).append(' ')
                    .append(format(centerX)).append(' ')
                    .append(format(centerY)).append(' ')
                    .append(format(box.width())).append(' ')
                    .append(format(box.height()))
                    .append('\n');
        }
        return text.toString();
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.6f", value);
    }
}
