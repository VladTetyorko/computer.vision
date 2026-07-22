package com.drones.vision.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Response body for {@code POST /api/devices/{deviceId}/stream}.
 *
 * <p>{@code viewUrl} is omitted from the JSON entirely (rather than
 * serialized as {@code null}) when the active {@code StreamPublisherPort}
 * has no viewing endpoint for this stream — see {@link
 * com.drones.vision.domain.port.out.StreamPublisherPort#viewUrl}.
 *
 * @param streamId identity of the newly started stream
 * @param viewUrl  where a viewer can watch the stream, or absent if the active publisher has no viewing endpoint
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record StartStreamResponse(String streamId, String viewUrl) {
}
