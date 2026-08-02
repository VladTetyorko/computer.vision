package com.drones.vision.application.stream;

/**
 * Result of {@link StreamService#updateConfig} (docs/CV-CONTROL-PLAN.md &sect;5).
 *
 * @param modelReArmed {@code true} only when the patch carried a {@code modelId} (see {@link
 *                     PipelineConfigPatch#modelId()}) that differed from the stream's running
 *                     model — the caller (vision-api, a later wave) surfaces this so the operator
 *                     knows a brief detection gap occurred while the detector swaps to the new
 *                     model. Every other knob is hot and never re-arms (frozen contract &sect;3):
 *                     a patch that only touches confidence/inference-fps/label-filter/detection-on-
 *                     off reports {@code false}.
 */
public record UpdateOutcome(boolean modelReArmed) {
}
