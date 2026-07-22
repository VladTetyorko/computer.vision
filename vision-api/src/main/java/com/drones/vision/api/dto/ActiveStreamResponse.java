package com.drones.vision.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * Response body element for {@code GET /api/streams}.
 *
 * <p>{@code viewUrl} is omitted from the JSON entirely (rather than
 * serialized as {@code null}) when the active {@code StreamPublisherPort}
 * has no viewing endpoint for this stream — see {@link
 * com.drones.vision.domain.port.out.StreamPublisherPort#viewUrl}.
 *
 * @param streamId  identity of the running stream
 * @param deviceId  the device the stream is sourced from
 * @param startedAt when the stream was started
 * @param viewUrl   where a viewer can watch the stream, or absent if the active publisher has no viewing endpoint
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ActiveStreamResponse(String streamId, String deviceId, Instant startedAt, String viewUrl) {
}
