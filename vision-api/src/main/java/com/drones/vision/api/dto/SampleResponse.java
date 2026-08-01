package com.drones.vision.api.dto;

import com.drones.vision.domain.model.TrainingSample;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/**
 * Wire representation of a {@link TrainingSample} (docs/CV-TRAINING-PLAN.md §3's frozen wire
 * contract). Annotations are pre-filled from the model's live detections on capture ({@code
 * source="MODEL"}).
 *
 * @param id          typed sample identity, as its canonical UUID string
 * @param datasetId   the owning dataset, as its canonical UUID string
 * @param streamId    the source stream, as its canonical UUID string
 * @param assetId     the source asset, as its canonical UUID string, absent if unresolved at
 *                    capture time
 * @param capturedAt  when the frame was captured
 * @param width       frame width in pixels
 * @param height      frame height in pixels
 * @param status      {@code PENDING}/{@code LABELED}/{@code DISCARDED}
 * @param labeledBy   who last confirmed/corrected this sample, as their canonical UUID string,
 *                    absent until reviewed
 * @param labeledAt   when this sample was last confirmed/corrected, absent until reviewed
 * @param annotations this sample's current annotations
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SampleResponse(String id, String datasetId, String streamId, String assetId, Instant capturedAt,
                              int width, int height, String status, String labeledBy, Instant labeledAt,
                              List<AnnotationResponse> annotations) {

    /**
     * Maps a domain {@link TrainingSample} to its wire representation.
     *
     * @param sample the sample to map
     * @return the response body for {@code sample}
     */
    public static SampleResponse from(TrainingSample sample) {
        return new SampleResponse(sample.id().value().toString(), sample.datasetId().value().toString(),
                sample.streamId().value().toString(),
                sample.assetId() == null ? null : sample.assetId().value().toString(), sample.capturedAt(),
                sample.width(), sample.height(), sample.status().name(),
                sample.labeledBy() == null ? null : sample.labeledBy().value().toString(), sample.labeledAt(),
                sample.annotations().stream().map(AnnotationResponse::from).toList());
    }
}
