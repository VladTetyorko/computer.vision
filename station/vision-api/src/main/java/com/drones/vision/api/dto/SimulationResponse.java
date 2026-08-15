package com.drones.vision.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Response body for {@code POST /api/simulations}.
 *
 * <p>{@code streamId}/{@code viewUrl}/{@code whepUrl} are omitted from the JSON entirely (rather
 * than serialized as {@code null}) when the simulation was not auto-started, or — for {@code
 * viewUrl}/{@code whepUrl} individually — when the active {@code StreamPublisherPort} has no
 * matching viewing endpoint for the stream, mirroring {@link StartStreamResponse} (docs/plans/done/MVP2-PLAN.md
 * §L). {@code whepUrl}, when present, is the media server's own origin URL, not app-relative like
 * {@code viewUrl} can be — see {@code whepUrl}'s port-level javadoc for why.
 *
 * @param assetId  the created simulated asset's identity, as a canonical UUID string
 * @param streamId the started stream's identity, or absent if {@code autoStart} was {@code false}
 * @param viewUrl  where a viewer can watch the stream over HLS, or absent if not streaming or the
 *                 active publisher has no viewing endpoint
 * @param whepUrl  where a viewer can watch the stream over WebRTC/WHEP (sub-second latency), or
 *                 absent if not streaming or the active publisher has no WebRTC viewing endpoint
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SimulationResponse(String assetId, String streamId, String viewUrl, String whepUrl) {
}
