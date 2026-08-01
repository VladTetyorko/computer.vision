package com.drones.vision.api.dto;

/**
 * Request body for {@code POST /api/datasets/{id}/train} (docs/CV-TRAINING-PLAN.md §8's frozen
 * wire contract).
 *
 * @param baseModel the checkpoint to fine-tune from; must not be blank ({@link
 *                  com.drones.vision.domain.model.TrainingJobSpec}'s own compact constructor)
 * @param epochs    number of training epochs to run; must be positive ({@link
 *                  com.drones.vision.domain.model.TrainingJobSpec}'s own compact constructor)
 */
public record StartTrainingJobRequest(String baseModel, int epochs) {
}
