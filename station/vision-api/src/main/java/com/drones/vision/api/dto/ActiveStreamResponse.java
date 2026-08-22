package com.drones.vision.api.dto;

import com.drones.vision.perception.application.stream.ActiveStream;
import com.drones.vision.perception.domain.model.DetectionState;
import com.drones.vision.perception.domain.model.StreamState;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * Response body element for {@code GET /api/streams}.
 *
 * <p>{@code viewUrl}/{@code whepUrl} are each omitted from the JSON entirely (rather than
 * serialized as {@code null}) when the active {@code StreamPublisherPort} has no matching viewing
 * endpoint for this stream — see {@link com.drones.vision.perception.domain.port.StreamPublisherPort#viewUrl}/
 * {@link com.drones.vision.perception.domain.port.StreamPublisherPort#whepUrl} (docs/plans/done/MVP2-PLAN.md §L).
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
 * @param state     whether this stream's <b>video</b> is actually flowing right now
 *                  (docs/plans/active/STREAM-STATE-PLAN.md &sect;2.5). Always serialized. Before this
 *                  existed a client could only infer liveness from this stream's presence in the
 *                  list, so a stalled or reconnecting source was indistinguishable from a healthy
 *                  one. {@code UNOBSERVED} is <b>not a fault</b> — it means a proxied source this
 *                  JVM never sees frames from — and a client that paints it red is reporting a
 *                  defect that does not exist
 * @param detectionEnabled the operator's own per-stream detect-on/off intent. Always serialized, and
 *                  <b>this is the field that ends the client-side guess</b>: it had no read surface
 *                  at all before this plan, which is why the cockpit's Detect switch rendered a
 *                  browser-local draft that could disagree with the stream in front of the operator.
 *                  Says what was <i>asked for</i>, never whether inference is running
 * @param detectionState which of the two CV gates explains the stream's current boxes-or-no-boxes
 *                  state (docs/plans/active/CV-DEMAND-PLAN.md &sect;3.6) — the same value {@code GET
 *                  .../tracks} serves, carried here so the fleet-wide poll answers it too rather
 *                  than forcing a per-stream request. Absent (omitted) only for a stream that
 *                  vanished between listing and reading it. Reports gating, never detector health
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ActiveStreamResponse(String streamId, String deviceId, Instant startedAt, String viewUrl,
                                    String whepUrl, StreamState state, boolean detectionEnabled,
                                    DetectionState detectionState) {

    /**
     * Maps one active stream to the wire.
     *
     * <p><b>This factory exists to stop two callers from drifting.</b> {@code StreamController#list}
     * (the REST poll) and {@code LiveUpdateRegistry#freshDevicesEnvelope} (the SSE {@code devices}
     * snapshot) describe the same streams to the same SPA, and the SPA <i>prefers the SSE path</i> —
     * so a field added to one and forgotten in the other is invisible in exactly the transport that
     * is normally in use. Both now call this.
     *
     * @param stream         the running stream
     * @param viewUrl        HLS viewing URL, or {@code null} if the publisher has none
     * @param whepUrl        WHEP viewing URL, or {@code null} if the publisher has none
     * @param detectionState which CV gate explains the stream's boxes, or {@code null} if the stream
     *                       vanished between listing and reading it
     * @return the response body describing it
     */
    public static ActiveStreamResponse from(ActiveStream stream, String viewUrl, String whepUrl,
                                             DetectionState detectionState) {
        return new ActiveStreamResponse(stream.streamId().value().toString(), stream.deviceId().value().toString(),
                stream.startedAt(), viewUrl, whepUrl, stream.state(), stream.detectionEnabled(),
                detectionState);
    }
}
