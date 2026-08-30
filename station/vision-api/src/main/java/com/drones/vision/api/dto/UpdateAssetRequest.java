package com.drones.vision.api.dto;

import com.drones.vision.warehouse.application.asset.AssetEdit;
import com.drones.vision.warehouse.domain.model.Identity;
import com.drones.vision.kernel.CategoryId;

import java.util.Map;

/**
 * Request body for {@code PATCH /api/assets/{id}}.
 *
 * <p>Partial by design: any field left out of the JSON stays as it is. That keeps a client
 * that only knows about {@code displayName} from blanking out attributes it never sent — the
 * usual failure mode of modelling an edit as a whole-record replacement.
 *
 * <p>Ownership, device membership, custody and lifecycle state are not editable here; they have
 * their own operations, so a rename can never quietly reassign, hand over, or retire an asset.
 * {@code identity} is the one exception (see {@link AssetEdit}'s own javadoc): when present it
 * replaces the asset's identity wholesale — its own {@code null} components mean "unknown", not
 * "unchanged" — mirroring {@link IdentityRequest}'s own semantics.
 *
 * @param displayName replacement name, or absent to keep the current one; must not be blank when present
 * @param category    replacement category slug, or absent to keep the current one
 * @param attributes  replacement attribute map, or absent to keep the current one
 * @param identity    replacement identity, or absent to keep the current one
 */
public record UpdateAssetRequest(String displayName, String category, Map<String, String> attributes,
                                  IdentityRequest identity) {

    /** No body at all: an edit that changes nothing. */
    public static final UpdateAssetRequest EMPTY = new UpdateAssetRequest(null, null, null, null);

    /**
     * Maps this request to the application-level edit.
     *
     * @return the partial edit to apply
     * @throws IllegalArgumentException if a present field is invalid (blank name, malformed category slug)
     */
    public AssetEdit toEdit() {
        CategoryId categoryId = category == null ? null : new CategoryId(category);
        Identity identityValue = identity == null ? null : identity.toIdentity();
        return new AssetEdit(displayName, categoryId, attributes, identityValue);
    }
}
