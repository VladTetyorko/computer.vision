package com.drones.vision.application.category;

import com.drones.vision.domain.model.DeviceCategory;
import com.drones.vision.domain.port.out.CategoryRepositoryPort;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Lists categories, sorted by slug.
 *
 * <p>The sort is deliberate: category pickers (the dropdown, discovery "Use" prefill) should
 * render in a stable, predictable order regardless of the repository's iteration or seed order.
 *
 * <h2>Threading</h2>
 * Holds no mutable state — safe to call concurrently.
 */
public final class DefaultCategoryService implements CategoryService {

    private final CategoryRepositoryPort categoryRepository;

    public DefaultCategoryService(CategoryRepositoryPort categoryRepository) {
        this.categoryRepository = Objects.requireNonNull(categoryRepository, "categoryRepository must not be null");
    }

    @Override
    public List<DeviceCategory> categories() {
        return categoryRepository.findAll().stream()
                .sorted(Comparator.comparing(category -> category.id().slug()))
                .toList();
    }
}
