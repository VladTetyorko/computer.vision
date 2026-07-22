package com.drones.vision.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Response body for {@code POST /api/simulations}.
 *
 * <p>{@code streamId}/{@code viewUrl} are omitted from the JSON entirely (rather than serialized
 * as {@code null}) when the simulation was not auto-started, or — for {@code viewUrl} — when the
 * active {@code StreamPublisherPort} has no viewing endpoint for the stream, mirroring {@link
 * StartStreamResponse}.
 *
 * @param assetId  the created simulated asset's identity, as a canonical UUID string
 * @param streamId the started stream's identity, or absent if {@code autoStart} was {@code false}
 * @param viewUrl  where a viewer can watch the stream, or absent if not streaming or the active
 *                 publisher has no viewing endpoint
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SimulationResponse(String assetId, String streamId, String viewUrl) {
}
