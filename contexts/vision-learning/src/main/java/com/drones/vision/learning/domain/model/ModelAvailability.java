package com.drones.vision.learning.domain.model;

/**
 * Whether a catalogued model id is actually loadable on the connected CV worker right now
 * (docs/plans/active/CV-SETTINGS-PLAN.md §3.2, §3.5 honesty rule 4) — a model the worker does not
 * have must read {@code MISSING_ON_WORKER}, never silently fall back to another checkpoint.
 *
 * <p>Deliberately <b>not</b> a field on {@link CvModelRecord}: availability is a live fact about
 * whichever worker is connected right now, not something a persisted catalogue row can know at
 * rest — the same reasoning that keeps "is this the active one" off {@code ModelRef} itself
 * (perception's value type for a model reference). Joining a catalogue row against the worker's
 * own reported roster to compute this is the application layer's job (the merge {@code
 * ModelRegistryService} performs), not this enum's. Pure marker, no behavior, no dedicated test
 * (same convention as {@link JobState}).
 */
public enum ModelAvailability {
    PRESENT,
    MISSING_ON_WORKER
}
