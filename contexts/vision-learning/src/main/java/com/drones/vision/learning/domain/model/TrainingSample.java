package com.drones.vision.learning.domain.model;

import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.StreamId;
import com.drones.vision.kernel.UserId;
import java.time.Instant;
import java.util.List;

/**
 * A captured frame plus its (evolving) annotations (docs/plans/done/CV-TRAINING-PLAN.md §1) — one row of a
 * {@link Dataset}.
 *
 * <p>Image bytes are <b>not</b> carried here — they are stored separately, keyed by {@link #id()},
 * via {@code SampleImageStorePort}, exactly as {@link AssetImage} bytes are keyed by {@link
 * AssetId} through {@code AssetImageRepositoryPort}. Keeping bytes out of this record means
 * listing/filtering samples never pays for image payloads.
 *
 * <p>On capture, {@code annotations} are the model's current detections mapped 1:1 to {@link
 * AnnotationSource#MODEL} and {@code status} is {@link SampleStatus#PENDING}; the operator then
 * confirms/corrects them, which replaces {@code annotations} and moves {@code status} to {@link
 * SampleStatus#LABELED} or {@link SampleStatus#DISCARDED}, stamping {@code labeledBy}/{@code
 * labeledAt}. That transition (including rejecting a label outside the dataset's {@link
 * Dataset#classes()}) is an application-layer concern ({@code LabelingService.label}), not this
 * record's — a {@code TrainingSample} itself allows any status/annotation combination its
 * component-level validation permits.
 *
 * @param id          typed training-sample identity
 * @param datasetId   the dataset this sample belongs to
 * @param streamId    the stream the frame was captured from
 * @param assetId     the owning asset at capture time, or {@code null} if the stream had none
 * @param capturedAt  when the frame was captured
 * @param width       frame width in pixels; must be positive
 * @param height      frame height in pixels; must be positive
 * @param annotations this sample's current annotations; defensively copied; may be empty (a
 *                    negative/background sample)
 * @param status      this sample's review state
 * @param labeledBy   who last confirmed/corrected this sample, or {@code null} until reviewed
 * @param labeledAt   when this sample was last confirmed/corrected, or {@code null} until reviewed
 */
public record TrainingSample(TrainingSampleId id, DatasetId datasetId, StreamId streamId, AssetId assetId,
                              Instant capturedAt, int width, int height, List<Annotation> annotations,
                              SampleStatus status, UserId labeledBy, Instant labeledAt) {

    public TrainingSample {
        if (id == null) {
            throw new IllegalArgumentException("TrainingSample id must not be null");
        }
        if (datasetId == null) {
            throw new IllegalArgumentException("TrainingSample datasetId must not be null");
        }
        if (streamId == null) {
            throw new IllegalArgumentException("TrainingSample streamId must not be null");
        }
        if (capturedAt == null) {
            throw new IllegalArgumentException("TrainingSample capturedAt must not be null");
        }
        if (width <= 0) {
            throw new IllegalArgumentException("TrainingSample width must be positive: " + width);
        }
        if (height <= 0) {
            throw new IllegalArgumentException("TrainingSample height must be positive: " + height);
        }
        if (annotations == null) {
            throw new IllegalArgumentException("TrainingSample annotations must not be null");
        }
        if (status == null) {
            throw new IllegalArgumentException("TrainingSample status must not be null");
        }
        annotations = List.copyOf(annotations);
    }
}
