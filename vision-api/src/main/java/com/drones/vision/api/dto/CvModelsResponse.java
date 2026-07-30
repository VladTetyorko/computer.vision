package com.drones.vision.api.dto;

import java.util.List;

/**
 * Response body for {@code GET /api/cv/models} (docs/CV-CONTROL-PLAN.md §4's frozen wire contract).
 *
 * @param models the model roster, {@code yolo26n.pt} first (the default per {@code
 *               PipelineConfig.defaults()})
 */
public record CvModelsResponse(List<CvModelResponse> models) {

    public CvModelsResponse {
        models = List.copyOf(models);
    }
}
