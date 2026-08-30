package com.drones.vision.warehouse.application.category;

import com.drones.vision.kernel.CategoryId;
import com.drones.vision.warehouse.domain.model.DeviceCategory;
import com.drones.vision.warehouse.domain.port.CategoryRepositoryPort;

import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;
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

    @Override
    public DeviceCategory create(CategorySpec spec) {
        Objects.requireNonNull(spec, "spec must not be null");
        if (categoryRepository.findById(spec.id()).isPresent()) {
            throw new IllegalStateException("Category already exists: " + spec.id().slug());
        }
        DeviceCategory category = new DeviceCategory(spec.id(), spec.name(), spec.parent(), spec.attributeHints(),
                spec.connected());
        return categoryRepository.save(category);
    }

    @Override
    public DeviceCategory update(CategoryId id, CategoryEdit edit) {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(edit, "edit must not be null");
        categoryRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Unknown category: " + id.slug()));
        DeviceCategory updated = new DeviceCategory(id, edit.name(), edit.parent(), edit.attributeHints(),
                edit.connected());
        return categoryRepository.save(updated);
    }
}
