package com.drones.vision.application;

import com.drones.vision.domain.model.DeviceCategory;

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
}
