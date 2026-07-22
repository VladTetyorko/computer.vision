package com.drones.vision.domain.model;

import java.util.List;

/**
 * A data-driven kind of device/asset (e.g. "drone", "fpv-drone", "ip-camera").
 *
 * <p>Replaces the old {@code DeviceType} enum: categories are reference data
 * behind {@code CategoryRepositoryPort}, seeded with defaults and extensible
 * at runtime — a new kind of robot is an INSERT, not a release. {@code
 * parent} supports an optional single-parent hierarchy (e.g. {@code
 * fpv-drone} → {@code drone}); {@code attributeHints} are UI suggestions for
 * an asset's free-form {@code attributes}, not a rigid schema.
 *
 * @param id             typed category identity
 * @param name           human-readable name; must not be blank
 * @param parent         parent category id, or {@code null} for a top-level category
 * @param attributeHints suggested attribute keys for assets in this category; defensively copied to an immutable list
 */
public record DeviceCategory(CategoryId id, String name, CategoryId parent, List<String> attributeHints) {

    public DeviceCategory {
        if (id == null) {
            throw new IllegalArgumentException("DeviceCategory id must not be null");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("DeviceCategory name must not be blank");
        }
        if (attributeHints == null) {
            throw new IllegalArgumentException("DeviceCategory attributeHints must not be null");
        }
        attributeHints = List.copyOf(attributeHints);
    }
}
