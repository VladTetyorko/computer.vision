package com.drones.vision.api.dto;

import com.drones.vision.domain.model.DetectionResult;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/**
 * Response body element for {@code GET /api/streams/{streamId}/detections} (docs/MVP1-PLAN.md
 * §C8 bullet 3) — one completed inference result. Also the {@code detections} SSE topic's payload
 * (docs/REALTIME-PLAN.md §4), so both surfaces gained the tracking object at once.
 *
 * <p>{@code @JsonInclude(NON_NULL)} covers exactly one field, {@code tracking}
 * (docs/TRACKING-PLAN.md §4.G) — every other field is always present, and a result from a stream
 * with tracking off serializes byte-identically to the pre-tracking wire.
 *
 * @param streamId        the stream this result belongs to, as a canonical UUID string
 * @param frameSequence   the source frame's sequence number this result was computed from
 * @param capturedAt      when the source frame was captured
 * @param inferenceMillis how long inference took, in milliseconds
 * @param detections      the objects detected on this frame; empty results are never persisted
 *                        (see {@code StreamPipeline}), so this is never empty in practice
 * @param tracking        this frame's duty-cycle facts (did the detector run, why, what the tracker
 *                        cost, which engine served, what is locked), or absent while tracking is off
 *                        for the stream
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DetectionResultResponse(String streamId, long frameSequence, Instant capturedAt, long inferenceMillis,
                                       List<DetectionResponse> detections, FrameTrackingResponse tracking) {

    /**
     * The canonical constructor before docs/TRACKING-PLAN.md wave T6 added {@code tracking}, kept as
     * a convenience constructor defaulting it to {@code null} ("tracking was off"), so every
     * pre-existing call site compiles unchanged.
     *
     * @param streamId        the stream this result belongs to
     * @param frameSequence   the source frame's sequence number
     * @param capturedAt      when the source frame was captured
     * @param inferenceMillis how long inference took, in milliseconds
     * @param detections      the objects detected on this frame
     */
    public DetectionResultResponse(String streamId, long frameSequence, Instant capturedAt, long inferenceMillis,
                                    List<DetectionResponse> detections) {
        this(streamId, frameSequence, capturedAt, inferenceMillis, detections, null);
    }

    /**
     * Maps a domain {@link DetectionResult} to its wire representation.
     *
     * @param result the result to map
     * @return the response body for {@code result}
     */
    public static DetectionResultResponse from(DetectionResult result) {
        return new DetectionResultResponse(result.streamId().value().toString(), result.frameSequence(),
                result.capturedAt(), result.inferenceLatency().toMillis(),
                result.detections().stream().map(DetectionResponse::from).toList(),
                result.tracking() == null ? null : FrameTrackingResponse.from(result.tracking()));
    }
}
