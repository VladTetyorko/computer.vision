package com.drones.vision.api.controller;

import com.drones.vision.api.dto.CategoryResponse;
import com.drones.vision.application.category.CategoryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Objects;

/**
 * Driving REST adapter for listing device/asset categories.
 *
 * <p>Constructor-injected with {@link CategoryService} only. Per the
 * hexagonal dependency rule (ARCHITECTURE.md §2, enforced by ArchUnit), this
 * module depends only on {@code vision-domain} and {@code
 * vision-application} — never on an adapter.
 */
@RestController
public class CategoryController {

    private final CategoryService categoryService;

    public CategoryController(CategoryService categoryService) {
        this.categoryService = Objects.requireNonNull(categoryService, "categoryService must not be null");
    }

    /**
     * Lists all defined categories — reference data the asset-creation UI
     * uses to populate its category dropdown.
     *
     * @return the currently defined categories
     */
    @GetMapping("/api/categories")
    public List<CategoryResponse> list() {
        return categoryService.categories().stream().map(CategoryResponse::from).toList();
    }
}
