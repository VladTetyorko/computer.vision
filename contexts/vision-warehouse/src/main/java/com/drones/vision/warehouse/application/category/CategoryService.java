package com.drones.vision.warehouse.application.category;

import com.drones.vision.kernel.CategoryId;
import com.drones.vision.warehouse.domain.model.DeviceCategory;

import java.util.List;

/**
 * The user-defined category tree assets are filed under.
 *
 * <p>One interface, one implementation ({@link DefaultCategoryService}).
 */
public interface CategoryService {

    /**
     * Lists every known category, in a stable order.
     *
     * @return an immutable snapshot
     */
    List<DeviceCategory> categories();

    /**
     * Creates a new category (docs/plans/active/WAREHOUSE-UX-PLAN.md §3.3 — {@code POST /api/categories}).
     *
     * @param spec the new category's fields
     * @return the persisted category
     * @throws IllegalStateException if a category with {@code spec.id()} already exists
     */
    DeviceCategory create(CategorySpec spec);

    /**
     * Replaces an existing category's mutable fields (docs/plans/active/WAREHOUSE-UX-PLAN.md §3.3 —
     * {@code PUT /api/categories/{id}}).
     *
     * @param id   the category to edit
     * @param edit the replacement fields
     * @return the persisted category
     * @throws java.util.NoSuchElementException if no category with {@code id} exists
     */
    DeviceCategory update(CategoryId id, CategoryEdit edit);
}
