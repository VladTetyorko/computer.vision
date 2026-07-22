package com.drones.vision.application;

import com.drones.vision.domain.model.CategoryId;

import java.util.List;
import java.util.Map;

/**
 * Everything needed to create an asset together with its device(s) in one act.
 *
 * @param displayName human-readable name (e.g. "my drone"); must not be blank
 * @param category    the asset's category
 * @param attributes  free-form key/value attributes; defensively copied
 * @param devices     the sources to register for it; must contain at least one
 */
public record AssetSpec(String displayName, CategoryId category, Map<String, String> attributes,
                         List<DeviceRegistration> devices) {

    public AssetSpec {
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("AssetSpec displayName must not be blank");
        }
        if (category == null) {
            throw new IllegalArgumentException("AssetSpec category must not be null");
        }
        if (attributes == null) {
            throw new IllegalArgumentException("AssetSpec attributes must not be null");
        }
        if (devices == null || devices.isEmpty()) {
            throw new IllegalArgumentException("AssetSpec devices must contain at least one device");
        }
        attributes = Map.copyOf(attributes);
        devices = List.copyOf(devices);
    }
}
