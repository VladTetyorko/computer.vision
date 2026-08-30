package com.drones.vision.learning.application;

import com.drones.vision.learning.domain.model.CvModelRecord;

import java.util.List;
import java.util.Objects;

/**
 * The deployment's static, config-seeded model list (docs/plans/active/CV-SETTINGS-PLAN.md §8 OQ5) —
 * {@link ModelRegistryService#models()}'s fallback when the connected CV worker cannot be reached at
 * all, so {@code GET /api/cv/models} never errors.
 *
 * <p>This is a settings-style constructor collaborator (java-clean-code SKILL.md §3), not a
 * hardcoded literal in this module: the actual content — today the same three-entry roster
 * {@code CvWiring#cvModelRoster} wires as a static bean for {@code CvModelsController} — is built by
 * the caller (vision-app's wiring) and handed in here, so this context never hardcodes deployment
 * config. A row from this catalogue carries no live worker information (see {@link
 * CatalogSource#CONFIG}) — {@link ModelRegistryService#models()} reports it back with {@link
 * com.drones.vision.learning.domain.model.ModelAvailability#PRESENT} unconditionally, since with the
 * worker unreachable there is nothing to check availability against; the {@link CatalogSource#CONFIG}
 * flag on the result is the actual "not verified live" signal, not the per-row availability.
 *
 * @param models the fallback roster, in display order; defensively copied
 */
public record ConfigModelCatalog(List<CvModelRecord> models) {

    public ConfigModelCatalog {
        Objects.requireNonNull(models, "models must not be null");
        models = List.copyOf(models);
    }
}
