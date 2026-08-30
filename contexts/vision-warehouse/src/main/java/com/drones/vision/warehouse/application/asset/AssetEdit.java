package com.drones.vision.warehouse.application.asset;

import com.drones.vision.kernel.CategoryId;
import com.drones.vision.warehouse.domain.model.Identity;

import java.util.Map;

/**
 * A partial edit to an asset: every field is optional, {@code null} means "leave unchanged".
 *
 * <p>Ownership, device membership, custody and lifecycle state are deliberately absent — they
 * change through their own operations, so a rename can never quietly reassign, hand over, or
 * retire an asset. {@code identity} (serial/make/model/registration) is an exception: unlike
 * custody, it is a descriptive fact about the airframe itself, not an authority-bearing state
 * transition, so it edits alongside {@code displayName}/{@code attributes}.
 *
 * @param displayName replacement name, or {@code null} to keep the current one
 * @param category    replacement category, or {@code null} to keep the current one
 * @param attributes  replacement attributes, or {@code null} to keep the current map
 * @param identity    replacement identity, or {@code null} to keep the current one
 */
public record AssetEdit(String displayName, CategoryId category, Map<String, String> attributes, Identity identity) {

    /** An edit that changes nothing — the identity of this operation. */
    public static final AssetEdit NOTHING = new AssetEdit(null, null, null, null);

    public AssetEdit {
        if (displayName != null && displayName.isBlank()) {
            throw new IllegalArgumentException("AssetEdit displayName must not be blank");
        }
        if (attributes != null) {
            attributes = Map.copyOf(attributes);
        }
    }

    /**
     * Whether this edit changes anything only a manager may change.
     *
     * <p>Splits one PATCH into two authority classes, because they are genuinely different acts.
     * {@code displayName} and {@code attributes} are the operator's own record of the aircraft they
     * fly — the tail number they call it by, the custom fields they keep on it — and locking a pilot
     * out of those makes the assignment useless for the person actually holding the controls.
     * {@code category} is not: it is fleet classification, it drives how the asset is treated
     * everywhere else, and it belongs to whoever manages the fleet.
     *
     * <p>Callers gate on this rather than on the endpoint, so a pilot renaming their own drone is
     * allowed while the same endpoint still refuses a re-categorisation (docs/plans/done/OPS-UX-PLAN.md §1).
     */
    public boolean changesManagedFields() {
        return category != null;
    }
}
