package com.drones.vision.api.dto;

import com.drones.vision.learning.application.DatasetSpec;
import com.drones.vision.kernel.CategoryId;

import java.util.List;

/**
 * Request body for {@code POST /api/datasets} (docs/plans/done/CV-TRAINING-PLAN.md §3's frozen wire
 * contract).
 *
 * @param name           human-readable name; must not be blank ({@link DatasetSpec}'s own compact
 *                       constructor)
 * @param targetCategory the {@link CategoryId} slug this dataset aims to improve detection of, or
 *                       absent/{@code null} if not tied to one category
 * @param classes        the ordered YOLO class list; absent/{@code null} defaults to an empty list
 *                       (a dataset may start with no classes and grow its vocabulary later)
 */
public record CreateDatasetRequest(String name, String targetCategory, List<String> classes) {

    /**
     * Maps this request to a {@link DatasetSpec}.
     *
     * @return the command record for {@link com.drones.vision.learning.application.DatasetService#create}
     * @throws IllegalArgumentException if {@link #name()} is blank ({@link DatasetSpec}'s own
     *                                   check), or {@link #targetCategory()} is present but not a
     *                                   valid lower-case-kebab slug ({@link CategoryId}'s own
     *                                   check)
     */
    public DatasetSpec toSpec() {
        CategoryId category =
                targetCategory == null || targetCategory.isBlank() ? null : new CategoryId(targetCategory);
        return new DatasetSpec(name, category, classes == null ? List.of() : classes);
    }
}
