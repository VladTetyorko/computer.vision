package com.drones.vision.warehouse.domain.model;

import com.drones.vision.kernel.CategoryId;
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
 * <p>{@code connected} (docs/plans/active/WAREHOUSE-UX-PLAN.md D4) says whether an asset in this
 * category wraps a live device at all: a drone or camera is connected and must have at least one
 * device (enforced in the application layer, since {@link com.drones.vision.warehouse.domain.model.Asset}
 * itself only knows a {@link CategoryId}, not this record); a battery, spare part, or radio is not
 * connected and legitimately has zero devices.
 *
 * @param id             typed category identity
 * @param name           human-readable name; must not be blank
 * @param parent         parent category id, or {@code null} for a top-level category
 * @param attributeHints suggested attribute keys for assets in this category; defensively copied to an immutable list
 * @param connected      whether an asset in this category must wrap at least one device
 */
public record DeviceCategory(CategoryId id, String name, CategoryId parent, List<String> attributeHints,
                              boolean connected) {

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
