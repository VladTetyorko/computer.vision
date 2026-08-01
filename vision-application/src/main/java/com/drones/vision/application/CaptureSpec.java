package com.drones.vision.application;

import com.drones.vision.domain.model.DatasetId;
import com.drones.vision.domain.model.StreamId;

import java.util.Objects;

/**
 * {@link LabelingService#capture}'s command record (docs/CV-TRAINING-PLAN.md §2/§3) — the wire
 * shape for {@code POST /api/streams/{streamId}/samples}, whose body carries only {@code
 * datasetId} (the stream id itself is a path parameter).
 *
 * @param streamId  the running stream to snapshot the current raw frame + latest detections from
 * @param datasetId the dataset the captured sample is added to
 */
public record CaptureSpec(StreamId streamId, DatasetId datasetId) {

    public CaptureSpec {
        Objects.requireNonNull(streamId, "streamId must not be null");
        Objects.requireNonNull(datasetId, "datasetId must not be null");
    }
}
