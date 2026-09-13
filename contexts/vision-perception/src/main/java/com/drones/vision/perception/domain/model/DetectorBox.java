package com.drones.vision.perception.domain.model;

import com.drones.vision.kernel.BoundingBox;

/**
 * One of the detector's own raw boxes for a traced frame (docs/plans/active/CV-ORCHESTRATION-PLAN.md
 * wave W5b, decision E23) — the model's output for this frame <strong>before</strong> association,
 * captured at the source so a saved trace can replay real detector noise through {@code
 * tools/trackeval} instead of the tracker's own already-associated output.
 *
 * <p>Deliberately not {@link Detection}: a detector has no identity, so this type carries no {@link
 * TrackRef} at all rather than one that is always {@code null} — the wire's own {@code
 * TracedDetection} message makes the same choice, for the same reason (a saved trace must never be
 * able to leak the tracker's own prior identity back in on replay).
 *
 * @param label      class label (e.g. {@code "person"}); must not be blank
 * @param confidence detection confidence, range [0,1]
 * @param box        normalized bounding box; must not be {@code null}
 */
public record DetectorBox(String label, double confidence, BoundingBox box) {

    public DetectorBox {
        if (label == null || label.isBlank()) {
            throw new IllegalArgumentException("DetectorBox label must not be blank");
        }
        if (Double.isNaN(confidence) || confidence < 0.0 || confidence > 1.0) {
            throw new IllegalArgumentException("DetectorBox confidence must be within [0,1]: " + confidence);
        }
        if (box == null) {
            throw new IllegalArgumentException("DetectorBox box must not be null");
        }
    }
}
