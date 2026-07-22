package com.drones.vision.api.dto;

import com.drones.vision.application.AssetEdit;
import com.drones.vision.domain.model.CategoryId;

import java.util.Map;

/**
 * Request body for {@code PATCH /api/assets/{id}}.
 *
 * <p>Partial by design: any field left out of the JSON stays as it is. That keeps a client
 * that only knows about {@code displayName} from blanking out attributes it never sent — the
 * usual failure mode of modelling an edit as a whole-record replacement.
 *
 * <p>Ownership, device membership and lifecycle state are not editable here; they have their
 * own operations, so a rename can never quietly reassign or retire an asset.
 *
 * @param displayName replacement name, or absent to keep the current one; must not be blank when present
 * @param category    replacement category slug, or absent to keep the current one
 * @param attributes  replacement attribute map, or absent to keep the current one
 */
public record UpdateAssetRequest(String displayName, String category, Map<String, String> attributes) {

    /** No body at all: an edit that changes nothing. */
    public static final UpdateAssetRequest EMPTY = new UpdateAssetRequest(null, null, null);

    /**
     * Maps this request to the application-level edit.
     *
     * @return the partial edit to apply
     * @throws IllegalArgumentException if a present field is invalid (blank name, malformed category slug)
     */
    public AssetEdit toEdit() {
        CategoryId categoryId = category == null ? null : new CategoryId(category);
        return new AssetEdit(displayName, categoryId, attributes);
    }
}
