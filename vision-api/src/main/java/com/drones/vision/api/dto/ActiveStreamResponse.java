package com.drones.vision.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * Response body element for {@code GET /api/streams}.
 *
 * <p>{@code viewUrl}/{@code whepUrl} are each omitted from the JSON entirely (rather than
 * serialized as {@code null}) when the active {@code StreamPublisherPort} has no matching viewing
 * endpoint for this stream — see {@link com.drones.vision.domain.port.out.StreamPublisherPort#viewUrl}/
 * {@link com.drones.vision.domain.port.out.StreamPublisherPort#whepUrl} (docs/plans/done/MVP2-PLAN.md §L).
 * {@code whepUrl}, when present, is the media server's own origin URL, not app-relative like
 * {@code viewUrl} can be — see {@code whepUrl}'s port-level javadoc for why (WHEP cannot be proxied
 * the way HLS segments are).
 *
 * @param streamId  identity of the running stream
 * @param deviceId  the device the stream is sourced from
 * @param startedAt when the stream was started
 * @param viewUrl   where a viewer can watch the stream over HLS, or absent if the active publisher has no viewing endpoint
 * @param whepUrl   where a viewer can watch the stream over WebRTC/WHEP (sub-second latency), or absent
 *                  if the active publisher has no WebRTC viewing endpoint
 * @param burnedIn  whether server-side overlay burn-in is actually active for this stream — see
 *                  {@link StartStreamResponse#burnedIn()}'s own javadoc for the full contract; same
 *                  "always serialized, never omitted" reasoning
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ActiveStreamResponse(String streamId, String deviceId, Instant startedAt, String viewUrl,
                                    String whepUrl, boolean burnedIn) {
}
