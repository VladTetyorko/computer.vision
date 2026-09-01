package com.drones.vision.learning.domain.model;

/**
 * What kind of inference one {@link CvModelRecord} performs (docs/plans/active/CV-SETTINGS-PLAN.md
 * §3.2) — the honest capability signal a model picker shows alongside {@link ModelRuntime}, so an
 * operator can tell a closed-set detector from a segmentation checkpoint from an open-vocabulary
 * model before choosing one. Pure marker, no behavior, no dedicated test (same convention as
 * {@link JobState}).
 */
public enum ModelTaskType {
    DETECT,
    SEGMENT,
    OPEN_VOCAB
}
