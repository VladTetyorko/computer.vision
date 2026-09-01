package com.drones.vision.learning.domain.model;

/**
 * Lifecycle state of one {@link CvModelRecord} row (docs/plans/active/CV-SETTINGS-PLAN.md §3.2) — a
 * catalogue entry moves {@code DRAFT} → {@code CANDIDATE} (a finished training run — never
 * automatically {@code LIVE}, see §8 OQ6) → {@code LIVE} (promoted; exactly one across the whole
 * catalogue at a time, enforced by {@code ModelRegistryService}, not this enum) → {@code RETIRED}
 * (demoted by a later promotion, kept so a rollback can find it again). Pure marker, no behavior,
 * no dedicated test (same convention as {@link JobState}).
 */
public enum ModelStatus {
    DRAFT,
    CANDIDATE,
    LIVE,
    RETIRED
}
