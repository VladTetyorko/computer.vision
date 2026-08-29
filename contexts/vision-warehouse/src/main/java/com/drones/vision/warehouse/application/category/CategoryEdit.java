package com.drones.vision.warehouse.application.category;

import com.drones.vision.kernel.CategoryId;

import java.util.List;

/**
 * A full replacement of a category's mutable fields (docs/plans/active/WAREHOUSE-UX-PLAN.md §3.3's
 * Categories table write half — {@code PUT /api/categories/{id}}).
 *
 * <p>Unlike {@link com.drones.vision.warehouse.application.asset.AssetEdit}, this is <b>not</b> a
 * partial patch: {@code parent} may legitimately be {@code null} (a top-level category), so a
 * per-field "{@code null} means unchanged" convention could never distinguish that from "clear the
 * parent" — a {@code PUT} whole-record replacement sidesteps the ambiguity entirely, matching how
 * {@link com.drones.vision.warehouse.domain.port.CategoryRepositoryPort#save} is itself an upsert.
 * A category's {@code id} is never edited — it names the row this edit targets.
 *
 * @param name           replacement name; must not be blank
 * @param parent         replacement parent category id, or {@code null} for a top-level category
 * @param connected      replacement connected flag
 * @param attributeHints replacement attribute hints; defensively copied
 */
public record CategoryEdit(String name, CategoryId parent, boolean connected, List<String> attributeHints) {

    public CategoryEdit {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("CategoryEdit name must not be blank");
        }
        if (attributeHints == null) {
            throw new IllegalArgumentException("CategoryEdit attributeHints must not be null");
        }
        attributeHints = List.copyOf(attributeHints);
    }
}
