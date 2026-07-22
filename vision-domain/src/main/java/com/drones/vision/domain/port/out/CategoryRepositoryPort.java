package com.drones.vision.domain.port.out;

import com.drones.vision.domain.model.CategoryId;
import com.drones.vision.domain.model.DeviceCategory;

import java.util.List;
import java.util.Optional;

/**
 * Driven port: persist and retrieve device/asset categories.
 *
 * <p>Categories are reference data (see {@link DeviceCategory}), not code:
 * this port is what lets a new kind of device be an INSERT rather than a
 * release, seeded with defaults and extensible at runtime.
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li>{@link #save(DeviceCategory)} inserts or updates (upsert by {@link
 *       CategoryId}) and returns the persisted category.</li>
 *   <li>{@link #findById(CategoryId)} returns {@link Optional#empty()}, never
 *       {@code null}, when no category with that id exists.</li>
 *   <li>{@link #findAll()} returns a snapshot; the returned list is not a
 *       live view of the store.</li>
 * </ul>
 *
 * <h2>Threading</h2>
 * Implementations must be safe for concurrent use — categories are read far
 * more often than written (typically only at seed time or admin edits), but
 * no caller assumes exclusive access.
 */
public interface CategoryRepositoryPort {

    /**
     * Inserts or updates a category.
     *
     * @param category the category to persist
     * @return the persisted category
     */
    DeviceCategory save(DeviceCategory category);

    /**
     * Finds a category by id.
     *
     * @param id the category id
     * @return the category, or {@link Optional#empty()} if none exists
     */
    Optional<DeviceCategory> findById(CategoryId id);

    /**
     * Lists all categories.
     *
     * @return an immutable snapshot of all categories
     */
    List<DeviceCategory> findAll();
}
