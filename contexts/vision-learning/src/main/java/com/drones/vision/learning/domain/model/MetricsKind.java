package com.drones.vision.learning.domain.model;

/**
 * Where a {@link ModelMetrics#map50()} figure came from (docs/plans/active/CV-SETTINGS-PLAN.md §3.2,
 * §3.5 honesty rule 5) — {@code TRAINING} is the training-time mAP@0.5 a fine-tune run reported
 * (not a held-out evaluation; see §7 non-goals — a real evaluation view is a later wave),
 * {@code WORKER} is a figure the CV worker itself reports for a model not trained through this
 * platform's loop (e.g. a bundled checkpoint). Carried alongside {@code map50} specifically so a
 * training-time number is never presented as an evaluation. Pure marker, no behavior, no dedicated
 * test (same convention as {@link JobState}).
 */
public enum MetricsKind {
    TRAINING,
    WORKER
}
