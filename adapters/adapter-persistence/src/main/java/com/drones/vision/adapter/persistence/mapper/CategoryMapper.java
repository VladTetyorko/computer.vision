package com.drones.vision.adapter.persistence.mapper;

import com.drones.vision.adapter.persistence.entity.CategoryEntity;
import com.drones.vision.kernel.CategoryId;
import com.drones.vision.warehouse.domain.model.DeviceCategory;

/**
 * {@link DeviceCategory} &harr; {@link CategoryEntity} mapping, extracted from {@code
 * JpaCategoryRepository} (docs/plans/active/LAYERING-REFACTOR-PLAN.md §3/§7 row C).
 */
public final class CategoryMapper {

    private CategoryMapper() {
    }

    public static CategoryEntity toEntity(DeviceCategory category) {
        String parentId = category.parent() == null ? null : category.parent().slug();
        return new CategoryEntity(category.id().slug(), category.name(), parentId, category.attributeHints());
    }

    public static DeviceCategory toDomain(CategoryEntity entity) {
        CategoryId parent = entity.parentId() == null ? null : new CategoryId(entity.parentId());
        return new DeviceCategory(new CategoryId(entity.id()), entity.name(), parent, entity.attributeHints());
    }
}
