package com.drones.vision.learning.application;

import java.util.List;
import java.util.Objects;

/**
 * The result of {@link ModelRegistryService#models()} — the joined roster plus where it actually
 * came from (docs/plans/active/CV-SETTINGS-PLAN.md §5.2, §8 OQ5).
 *
 * @param models every known model, joined with worker availability ({@link CatalogSource#REGISTRY})
 *               or the static config fallback ({@link CatalogSource#CONFIG})
 * @param source which of the two paths produced {@code models}
 */
public record CvModelCatalog(List<CvModelView> models, CatalogSource source) {

    public CvModelCatalog {
        Objects.requireNonNull(models, "models must not be null");
        Objects.requireNonNull(source, "source must not be null");
        models = List.copyOf(models);
    }
}
