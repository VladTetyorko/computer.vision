package com.drones.vision.api.dto;

import com.drones.vision.warehouse.domain.model.DeviceCategory;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Response body element for {@code GET /api/categories}.
 *
 * <p>{@code parent} is omitted from the JSON entirely (rather than
 * serialized as {@code null}) for a top-level category.
 *
 * @param slug           category identity (kebab-case slug)
 * @param name           human-readable name
 * @param parent         parent category slug, or absent for a top-level category
 * @param attributeHints suggested attribute keys for assets in this category (UI suggestions, not a rigid schema)
 * @param connected      whether an asset in this category must wrap at least one device
 *                       (docs/plans/active/WAREHOUSE-UX-PLAN.md D4)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CategoryResponse(String slug, String name, String parent, List<String> attributeHints,
                                boolean connected) {

    /**
     * Maps a domain {@link DeviceCategory} to its wire representation.
     *
     * @param category the category to map
     * @return the response body element for {@code category}
     */
    public static CategoryResponse from(DeviceCategory category) {
        return new CategoryResponse(
                category.id().slug(),
                category.name(),
                category.parent() != null ? category.parent().slug() : null,
                category.attributeHints(),
                category.connected());
    }
}
