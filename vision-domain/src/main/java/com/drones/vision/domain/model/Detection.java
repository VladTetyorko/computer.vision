package com.drones.vision.domain.model;

/**
 * A single object detection produced by a model on one frame.
 *
 * <p>{@code track} (docs/plans/done/TRACKING-PLAN.md §4.B) is {@code null} when tracking is off for this
 * stream, or when this particular detection has not (yet) been associated with a track —
 * "untracked" has exactly one spelling at this layer, a null reference, never a sentinel {@link
 * TrackRef} with {@code trackId == 0} (docs/extracts/TRACKING-ORCHESTRATION.md §6 rule 2).
 *
 * @param label      class label (e.g. {@code "person"}); must not be blank
 * @param confidence detection confidence, range [0,1]
 * @param box        normalized bounding box
 * @param model      model that produced this detection
 * @param track      per-detection track facts, or {@code null} if untracked
 */
public record Detection(String label, double confidence, BoundingBox box, ModelRef model, TrackRef track) {

    public Detection {
        if (label == null || label.isBlank()) {
            throw new IllegalArgumentException("Detection label must not be blank");
        }
        if (Double.isNaN(confidence) || confidence < 0.0 || confidence > 1.0) {
            throw new IllegalArgumentException("Detection confidence must be within [0,1]: " + confidence);
        }
        if (box == null) {
            throw new IllegalArgumentException("Detection box must not be null");
        }
        if (model == null) {
            throw new IllegalArgumentException("Detection model must not be null");
        }
    }

    /**
     * Convenience constructor for an untracked detection (tracking off for the stream, or not yet
     * associated) — defaults {@link #track()} to {@code null}, the same "N-1-arg convenience ctor"
     * idiom used elsewhere ({@code Telemetry}'s 8-arg ctor, {@code AssetUsage}'s 7-arg ctor). This
     * was the canonical constructor before docs/plans/done/TRACKING-PLAN.md §4.B added {@link #track()};
     * every pre-existing 4-arg call site compiles unchanged.
     */
    public Detection(String label, double confidence, BoundingBox box, ModelRef model) {
        this(label, confidence, box, model, null);
    }
}
