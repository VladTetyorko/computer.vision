package com.drones.vision.application.asset;

import com.drones.vision.domain.model.CategoryId;

import java.util.Map;

/**
 * A partial edit to an asset: every field is optional, {@code null} means "leave unchanged".
 *
 * <p>Ownership, device membership and lifecycle state are deliberately absent — they change
 * through their own operations, so a rename can never quietly reassign or retire an asset.
 *
 * @param displayName replacement name, or {@code null} to keep the current one
 * @param category    replacement category, or {@code null} to keep the current one
 * @param attributes  replacement attributes, or {@code null} to keep the current map
 */
public record AssetEdit(String displayName, CategoryId category, Map<String, String> attributes) {

    /** An edit that changes nothing — the identity of this operation. */
    public static final AssetEdit NOTHING = new AssetEdit(null, null, null);

    public AssetEdit {
        if (displayName != null && displayName.isBlank()) {
            throw new IllegalArgumentException("AssetEdit displayName must not be blank");
        }
        if (attributes != null) {
            attributes = Map.copyOf(attributes);
        }
    }
}
