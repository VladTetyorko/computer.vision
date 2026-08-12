package com.drones.vision.learning.application;

import com.drones.vision.kernel.UsageId;
import com.drones.vision.learning.domain.model.DatasetId;

import java.util.Objects;

/**
 * {@link LabelingService#captureFromReplay}'s command record (docs/plans/done/CV-TRAINING-V2-PLAN.md §4) —
 * the wire shape for {@code POST /api/usages/{usageId}/samples}, whose body carries only {@code
 * datasetId}/{@code atSeconds} (the usage id itself is a path parameter).
 *
 * @param usageId    the finished usage whose recording to pull a frame from
 * @param datasetId  the dataset the captured sample is added to
 * @param atSeconds  offset from the usage's own {@code startedAt} — the same anchor {@code
 *                   ReplayService#recordingFor} uses for its clip window and the replay UI's
 *                   {@code videoOffsetSeconds} already produces client-side; must be finite and
 *                   {@code >= 0}
 */
public record ReplayCaptureSpec(UsageId usageId, DatasetId datasetId, double atSeconds) {

    public ReplayCaptureSpec {
        Objects.requireNonNull(usageId, "usageId must not be null");
        Objects.requireNonNull(datasetId, "datasetId must not be null");
        if (!Double.isFinite(atSeconds) || atSeconds < 0) {
            throw new IllegalArgumentException(
                    "ReplayCaptureSpec atSeconds must be finite and >= 0, got: " + atSeconds);
        }
    }
}
