package com.drones.vision.app;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuration for the CV model-improvement training loop ({@code vision.training.*}),
 * per docs/CV-TRAINING-PLAN.md §3, Wave T4, as delta'd by docs/CV-TRAINING-V2-PLAN.md §7.
 *
 * <p>Selected by {@link TrainingWiringConfiguration}: {@link #enabled()} {@code false} (the
 * default, today's behavior) keeps every training bean/controller entirely absent — {@code
 * DatasetController}/{@code LabelingController}/{@code TrainingJobController} routes 404, mirroring
 * {@code LiveController}'s own {@code vision.live.enabled} gating; {@code true} wires {@link
 * com.drones.vision.application.DatasetService}/{@link com.drones.vision.application.LabelingService}
 * plus the gRPC {@code DatasetUploadPort} dataset delivers over.
 *
 * <p>{@code exportDir}/{@code DEFAULT_EXPORT_DIR} are gone (docs/CV-TRAINING-V2-PLAN.md §A/§3): the
 * manual filesystem export step {@code FilesystemDatasetExport} used to write to is deleted —
 * dataset delivery to the training host is now an implicit part of {@code POST
 * /api/datasets/{id}/train}, over a gRPC upload with no on-disk artifact at all.
 *
 * @param enabled whether to wire the training controllers/services instead of leaving them absent;
 *                default {@code false}
 */
@ConfigurationProperties(prefix = "vision.training")
public record VisionTrainingProperties(@DefaultValue("false") boolean enabled) {
}
