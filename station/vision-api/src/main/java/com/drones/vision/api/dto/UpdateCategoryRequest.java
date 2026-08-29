package com.drones.vision.api.dto;

import com.drones.vision.warehouse.application.category.CategoryEdit;
import com.drones.vision.kernel.CategoryId;

import java.util.List;

/**
 * Request body for {@code PUT /api/categories/{id}} (docs/plans/active/WAREHOUSE-UX-PLAN.md §3.3's
 * Categories table write half).
 *
 * <p>A whole-record replacement, not a partial patch — see {@link CategoryEdit}'s own javadoc for
 * why {@code parentId} rules out the usual "{@code null} means unchanged" convention.
 *
 * @param name           replacement name; must not be blank
 * @param parentId       replacement parent category slug, or {@code null}/absent for a top-level category
 * @param connected      replacement connected flag
 * @param attributeHints replacement attribute hints; may be {@code null} (treated as empty)
 */
public record UpdateCategoryRequest(String name, String parentId, boolean connected, List<String> attributeHints) {

    /**
     * Validates and converts this request into a {@link CategoryEdit}.
     *
     * @return the input for {@code CategoryService#update}
     * @throws IllegalArgumentException if {@code name} is blank
     */
    public CategoryEdit toEdit() {
        CategoryId parent = parentId == null || parentId.isBlank() ? null : new CategoryId(parentId);
        return new CategoryEdit(name, parent, connected, attributeHints == null ? List.of() : attributeHints);
    }
}
