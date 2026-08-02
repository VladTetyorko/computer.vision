package com.drones.vision.api.dto;

import com.drones.vision.application.training.RegisteredModel;

/**
 * Wire representation of one {@link RegisteredModel} row (docs/CV-TRAINING-PLAN.md §8) — a known
 * CV model reference plus whether it is the one currently promoted/live.
 *
 * <p>No {@code @JsonInclude(NON_NULL)} here — every field is always present, mirroring {@link
 * CvModelResponse}'s own "no nullable fields" posture.
 *
 * @param id      the model identifier (e.g. a checkpoint filename)
 * @param version the model version
 * @param active  whether this is the registry's current active/production model
 */
public record RegisteredModelResponse(String id, String version, boolean active) {

    /**
     * Maps a domain {@link RegisteredModel} to its wire representation.
     *
     * @param model the registered model to map
     * @return the response body for {@code model}
     */
    public static RegisteredModelResponse from(RegisteredModel model) {
        return new RegisteredModelResponse(model.ref().id(), model.ref().version(), model.active());
    }
}
