package com.drones.vision.api.dto;

import com.drones.vision.learning.application.PromotionResult;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Response body for {@code POST /api/cv/registry/models/{id}/promote} and {@code POST
 * /api/cv/registry/rollback} (docs/plans/active/CV-SETTINGS-PLAN.md §5.2's frozen wire contract) —
 * both operations return the same shape, the row now {@code LIVE} and which row (if any) it demoted.
 *
 * @param id               the model id now {@code LIVE} — named {@code id}, not {@code modelId}, per
 *                          the frozen wire contract, unlike {@link PromotionResult#modelId()} itself
 * @param version           its version
 * @param status            always {@code "LIVE"}
 * @param previousModelId   the model id this operation demoted to {@code RETIRED}, or {@code null}
 *                          if none was
 * @param previousVersion   the demoted model's version, or {@code null}
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PromotionResultResponse(String id, String version, String status, String previousModelId,
                                       String previousVersion) {

    /**
     * @param result the domain outcome
     * @return the wire representation
     */
    public static PromotionResultResponse from(PromotionResult result) {
        return new PromotionResultResponse(result.modelId(), result.version(), result.status().name(),
                result.previousModelId(), result.previousVersion());
    }
}
