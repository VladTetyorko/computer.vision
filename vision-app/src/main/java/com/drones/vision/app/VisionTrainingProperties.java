package com.drones.vision.app;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuration for the CV model-improvement training loop ({@code vision.training.*}),
 * per docs/CV-TRAINING-PLAN.md §3, Wave T4.
 *
 * <p>Selected by {@link TrainingWiringConfiguration}: {@link #enabled()} {@code false} (the
 * default, today's behavior) keeps every training bean/controller entirely absent — {@code
 * DatasetController}/{@code LabelingController} routes 404, mirroring {@code LiveController}'s own
 * {@code vision.live.enabled} gating; {@code true} wires {@link
 * com.drones.vision.application.DatasetService}/{@link
 * com.drones.vision.application.LabelingService} plus a {@code FilesystemDatasetExport}
 * (adapter-persistence) rooted at {@link #exportDir()}.
 *
 * @param enabled   whether to wire the training controllers/services instead of leaving them
 *                  absent; default {@code false}
 * @param exportDir filesystem directory a completed dataset export's zip is written under (one
 *                  {@code <exportDir>/<datasetId>/<exportId>.zip} per export — see {@code
 *                  FilesystemDatasetExport}); only read when {@link #enabled()} is {@code true};
 *                  default {@value #DEFAULT_EXPORT_DIR}
 */
@ConfigurationProperties(prefix = "vision.training")
public record VisionTrainingProperties(@DefaultValue("false") boolean enabled,
                                        @DefaultValue(VisionTrainingProperties.DEFAULT_EXPORT_DIR) String exportDir) {

    static final String DEFAULT_EXPORT_DIR = "data/training-exports";

    public VisionTrainingProperties {
        if (exportDir == null || exportDir.isBlank()) {
            throw new IllegalArgumentException("vision.training.export-dir must not be blank");
        }
    }
}
