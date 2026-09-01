package com.drones.vision.warehouse.application.category;

import com.drones.vision.kernel.CategoryId;

import java.util.List;

/**
 * Everything needed to create a new category (docs/plans/active/WAREHOUSE-UX-PLAN.md §3.3's
 * Categories table write half — {@code POST /api/categories}).
 *
 * @param id             the new category's id (kebab-case slug); must be unique
 * @param name           human-readable name; must not be blank
 * @param parent         parent category id, or {@code null} for a top-level category
 * @param connected      whether an asset in this category must wrap at least one device
 * @param attributeHints suggested attribute keys for assets in this category; defensively copied
 */
public record CategorySpec(CategoryId id, String name, CategoryId parent, boolean connected,
                            List<String> attributeHints) {

    public CategorySpec {
        if (id == null) {
            throw new IllegalArgumentException("CategorySpec id must not be null");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("CategorySpec name must not be blank");
        }
        if (attributeHints == null) {
            throw new IllegalArgumentException("CategorySpec attributeHints must not be null");
        }
        attributeHints = List.copyOf(attributeHints);
    }
}
