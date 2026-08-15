package com.drones.vision.app.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuration for the CV model-improvement training loop ({@code vision.training.*}),
 * per docs/plans/done/CV-TRAINING-PLAN.md §3, Wave T4, as delta'd by docs/plans/done/CV-TRAINING-V2-PLAN.md §7.
 *
 * <p>Selected by {@code wiring.TrainingWiring}: {@link #enabled()} {@code false} (the
 * default, today's behavior) keeps every training bean/controller entirely absent — {@code
 * DatasetController}/{@code LabelingController}/{@code TrainingJobController} routes 404, mirroring
 * {@code LiveController}'s own {@code vision.live.enabled} gating; {@code true} wires {@code
 * com.drones.vision.learning.application.DatasetService}/{@code
 * com.drones.vision.learning.application.LabelingService} plus the gRPC {@code DatasetUploadPort}
 * dataset delivers over.
 *
 * @param enabled whether to wire the training controllers/services instead of leaving them absent;
 *                default {@code false}
 */
@ConfigurationProperties(prefix = "vision.training")
public record VisionTrainingProperties(@DefaultValue("false") boolean enabled) {
}
