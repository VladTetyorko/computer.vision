package com.drones.vision.api.controller;

import com.drones.vision.api.security.CurrentUser;
import com.drones.vision.api.security.OpenByDesign;
import com.drones.vision.api.dto.CategoryResponse;
import com.drones.vision.api.dto.CreateCategoryRequest;
import com.drones.vision.api.dto.UpdateCategoryRequest;
import com.drones.vision.platform.AccessDeniedException;
import com.drones.vision.warehouse.application.category.CategoryService;
import com.drones.vision.kernel.CategoryId;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Objects;

/**
 * Driving REST adapter for the device/asset category taxonomy: {@link #list} is open reference
 * data every authenticated caller may read; {@link #create}/{@link #update} are org-wide
 * management acts (docs/plans/active/WAREHOUSE-UX-PLAN.md §3.3's Categories table write half),
 * gated the same way {@link AssetController#create} gates registering a brand-new asset — {@link
 * com.drones.vision.platform.VisibilityScope#canManageOrg() scope().canManageOrg()} — since a
 * category has no per-instance {@link com.drones.vision.kernel.Ownership} to {@code canManage}
 * against.
 *
 * <p>Constructor-injected with {@link CategoryService} and {@link CurrentUser}. Per the hexagonal
 * dependency rule (ARCHITECTURE.md §2, enforced by ArchUnit), this module depends only on {@code
 * vision-domain} and {@code vision-application} — never on an adapter.
 */
@RestController
public class CategoryController {

    private final CategoryService categoryService;
    private final CurrentUser currentUser;

    public CategoryController(CategoryService categoryService, CurrentUser currentUser) {
        this.categoryService = Objects.requireNonNull(categoryService, "categoryService must not be null");
        this.currentUser = Objects.requireNonNull(currentUser, "currentUser must not be null");
    }

    /**
     * Lists all defined categories — reference data the asset-creation UI
     * uses to populate its category dropdown.
     *
     * @return the currently defined categories
     */
    @OpenByDesign(reason = "Deployment-wide reference data (the category taxonomy); carries no per-asset or per-user information.")
    @GetMapping("/api/categories")
    public List<CategoryResponse> list() {
        return categoryService.categories().stream().map(CategoryResponse::from).toList();
    }

    /**
     * Creates a new category.
     *
     * @param request the new category's fields
     * @return the created category
     * @throws AccessDeniedException if the caller may not manage the org's reference data
     */
    @PostMapping("/api/categories")
    @ResponseStatus(HttpStatus.CREATED)
    public CategoryResponse create(@RequestBody CreateCategoryRequest request) {
        if (!currentUser.scope().canManageOrg()) {
            throw new AccessDeniedException("Not permitted to create categories");
        }
        return CategoryResponse.from(categoryService.create(request.toSpec()));
    }

    /**
     * Replaces an existing category's mutable fields.
     *
     * @param id      the category to edit, as its kebab-case slug
     * @param request the replacement fields
     * @return the updated category
     * @throws AccessDeniedException if the caller may not manage the org's reference data
     */
    @PutMapping("/api/categories/{id}")
    public CategoryResponse update(@PathVariable String id, @RequestBody UpdateCategoryRequest request) {
        if (!currentUser.scope().canManageOrg()) {
            throw new AccessDeniedException("Not permitted to edit categories");
        }
        return CategoryResponse.from(categoryService.update(new CategoryId(id), request.toEdit()));
    }
}
