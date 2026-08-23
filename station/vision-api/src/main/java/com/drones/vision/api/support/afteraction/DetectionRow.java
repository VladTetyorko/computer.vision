package com.drones.vision.api.support.afteraction;

import java.time.Instant;
import java.util.Objects;

/**
 * One flattened detection — a single {@code detections.csv} data row (docs/plans/active/
 * AFTER-ACTION-PLAN.md &sect;3.2): {@code capturedAt,label,confidence,x,y,width,height,trackId}.
 *
 * <p>{@code com.drones.vision.perception.domain.model.DetectionResult} carries a whole frame's
 * detections as one object; a CSV needs one row per detected object, not one row per frame — {@link
 * AfterActionAssembler#flattenDetections} does that flattening once, so both the manifest's
 * {@code count} (docs/plans/done/AFTER-ACTION-PLAN.md &sect;3.1) and the archive's CSV row count
 * agree on what "a detection" counts as.
 *
 * @param capturedAt the source frame's capture timestamp
 * @param label      the detected class label
 * @param confidence detection confidence, {@code [0,1]}
 * @param x          normalized bounding-box left edge, {@code [0,1]}
 * @param y          normalized bounding-box top edge, {@code [0,1]}
 * @param width      normalized bounding-box width, {@code [0,1]}
 * @param height     normalized bounding-box height, {@code [0,1]}
 * @param trackId    the track this detection was associated with, or {@code null} if untracked —
 *                   an empty CSV cell, never a {@code 0} sentinel (matching {@code TrackRef}'s own
 *                   "0 must never reach this type" rule)
 */
public record DetectionRow(Instant capturedAt, String label, double confidence, double x, double y, double width,
                            double height, Long trackId) {

    public DetectionRow {
        Objects.requireNonNull(capturedAt, "capturedAt must not be null");
        Objects.requireNonNull(label, "label must not be null");
    }
}
