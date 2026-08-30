package com.drones.vision.learning.application;

import com.drones.vision.learning.domain.model.ModelStatus;

import java.util.Objects;

/**
 * The outcome of {@link ModelRegistryService#promote}/{@link ModelRegistryService#rollback}
 * (docs/plans/active/CV-SETTINGS-PLAN.md §5.2) — both operations demote exactly one row to {@link
 * ModelStatus#RETIRED} and promote exactly one row to {@link ModelStatus#LIVE}, so both return the
 * same shape: which row is now live, and which row (if any) this operation demoted.
 *
 * @param modelId          the model id now {@link ModelStatus#LIVE}
 * @param version          its version
 * @param status           always {@link ModelStatus#LIVE} — carried on the wire rather than assumed,
 *                         matching the frozen §5.2 response shape
 * @param previousModelId  the model id this operation demoted to {@link ModelStatus#RETIRED}, or
 *                         {@code null} if none was demoted (e.g. promoting the model that was
 *                         already live); must be {@code null} exactly when {@code previousVersion} is
 * @param previousVersion  the demoted model's version, or {@code null}; must be {@code null} exactly
 *                         when {@code previousModelId} is
 */
public record PromotionResult(String modelId, String version, ModelStatus status, String previousModelId,
                               String previousVersion) {

    public PromotionResult {
        if (modelId == null || modelId.isBlank()) {
            throw new IllegalArgumentException("PromotionResult modelId must not be blank");
        }
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException("PromotionResult version must not be blank");
        }
        Objects.requireNonNull(status, "PromotionResult status must not be null");
        if ((previousModelId == null) != (previousVersion == null)) {
            throw new IllegalArgumentException(
                    "PromotionResult previousModelId and previousVersion must both be null or both be set");
        }
    }
}
