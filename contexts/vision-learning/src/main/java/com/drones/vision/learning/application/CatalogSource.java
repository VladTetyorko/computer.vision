package com.drones.vision.learning.application;

/**
 * Where a {@link CvModelCatalog} actually came from (docs/plans/active/CV-SETTINGS-PLAN.md §5.2,
 * §8 OQ5) — the honesty flag a picker renders alongside the roster itself.
 *
 * <p>{@link #REGISTRY} means {@link ModelRegistryService#models()} reached the connected CV worker
 * and merged its live roster with the platform's own {@code cv_models} rows. {@link #CONFIG} means
 * the worker could not be reached at all, so the catalogue fell back to {@link ConfigModelCatalog}
 * — the deployment's static, config-seeded list, with no live availability/stage information mixed
 * in. {@code GET /api/cv/models} must never throw (§8 OQ5); this is the flag that lets a caller
 * tell "verified against the worker just now" from "the static fallback" apart, honesty rule 4
 * (never silently substitute).
 */
public enum CatalogSource {
    REGISTRY,
    CONFIG
}
