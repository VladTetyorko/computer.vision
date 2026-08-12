package com.drones.vision.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Response body for {@code POST /api/devices/{deviceId}/stream}.
 *
 * <p>{@code viewUrl}/{@code whepUrl} are each omitted from the JSON entirely (rather than
 * serialized as {@code null}) when the active {@code StreamPublisherPort} has no matching viewing
 * endpoint for this stream — see {@link com.drones.vision.domain.port.out.StreamPublisherPort#viewUrl}/
 * {@link com.drones.vision.domain.port.out.StreamPublisherPort#whepUrl} (docs/plans/done/MVP2-PLAN.md §L).
 * {@code whepUrl}, when present, is the media server's own origin URL, not app-relative like
 * {@code viewUrl} can be — see {@code whepUrl}'s port-level javadoc for why (WHEP cannot be proxied
 * the way HLS segments are).
 *
 * @param streamId identity of the newly started stream
 * @param viewUrl  where a viewer can watch the stream over HLS, or absent if the active publisher has no viewing endpoint
 * @param whepUrl  where a viewer can watch the stream over WebRTC/WHEP (sub-second latency), or absent
 *                 if the active publisher has no WebRTC viewing endpoint
 * @param burnedIn whether server-side overlay burn-in is actually active for this stream
 *                 (docs/plans/active/MEDIA-SOT-PLAN.md &sect;5.4) — {@code false} exactly when nothing burns
 *                 detection boxes into the published video (a proxied source, or a pull-transport
 *                 stream), {@code true} otherwise. A primitive, always serialized — never omitted like
 *                 {@code viewUrl}/{@code whepUrl} — because an absent value reads as {@code true} to
 *                 the client (docs/plans/done wave M8), which would misreport the {@code false} case
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record StartStreamResponse(String streamId, String viewUrl, String whepUrl, boolean burnedIn) {
}
