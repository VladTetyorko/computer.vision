package com.drones.vision.api.dto;

import com.drones.vision.domain.model.DetectionResult;

import java.time.Instant;
import java.util.List;

/**
 * Response body element for {@code GET /api/streams/{streamId}/detections} (docs/MVP1-PLAN.md
 * §C8 bullet 3) — one completed inference result.
 *
 * @param streamId        the stream this result belongs to, as a canonical UUID string
 * @param frameSequence   the source frame's sequence number this result was computed from
 * @param capturedAt      when the source frame was captured
 * @param inferenceMillis how long inference took, in milliseconds
 * @param detections      the objects detected on this frame; empty results are never persisted
 *                        (see {@code StreamPipeline}), so this is never empty in practice
 */
public record DetectionResultResponse(String streamId, long frameSequence, Instant capturedAt, long inferenceMillis,
                                       List<DetectionResponse> detections) {

    /**
     * Maps a domain {@link DetectionResult} to its wire representation.
     *
     * @param result the result to map
     * @return the response body for {@code result}
     */
    public static DetectionResultResponse from(DetectionResult result) {
        return new DetectionResultResponse(result.streamId().value().toString(), result.frameSequence(),
                result.capturedAt(), result.inferenceLatency().toMillis(),
                result.detections().stream().map(DetectionResponse::from).toList());
    }
}
