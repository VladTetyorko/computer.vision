package com.drones.vision.api.dto;

import com.drones.vision.warehouse.application.category.CategorySpec;
import com.drones.vision.kernel.CategoryId;

import java.util.List;

/**
 * Request body for {@code POST /api/categories} (docs/plans/active/WAREHOUSE-UX-PLAN.md §3.3's
 * Categories table write half).
 *
 * @param id             the new category's id (kebab-case slug); must be unique
 * @param name           human-readable name; must not be blank
 * @param parentId       parent category slug, or {@code null}/absent for a top-level category
 * @param connected      whether an asset in this category must wrap at least one device
 * @param attributeHints suggested attribute keys for assets in this category; may be {@code null} (treated
 *                       as empty)
 */
public record CreateCategoryRequest(String id, String name, String parentId, boolean connected,
                                     List<String> attributeHints) {

    /**
     * Validates and converts this request into a {@link CategorySpec}.
     *
     * @return the input for {@code CategoryService#create}
     * @throws IllegalArgumentException if {@code id} is not a lower-case-kebab slug or {@code name} is blank
     */
    public CategorySpec toSpec() {
        CategoryId parent = parentId == null || parentId.isBlank() ? null : new CategoryId(parentId);
        return new CategorySpec(new CategoryId(id), name, parent, connected,
                attributeHints == null ? List.of() : attributeHints);
    }
}
