package com.drones.vision.api.dto;

import com.drones.vision.warehouse.domain.model.Identity;

/**
 * Identity facts supplied on a write request — shared shape for {@link CreateAssetRequest} and
 * {@link UpdateAssetRequest} (docs/plans/active/WAREHOUSE-UX-PLAN.md D1). Mirrors {@link
 * Identity}'s own optionality: any field left {@code null} is unknown, not "unchanged" — an
 * {@code identity} object present on the request always replaces the asset's identity wholesale.
 *
 * @param serialNumber the manufacturer's serial number, or {@code null} if unknown
 * @param make         the manufacturer, or {@code null} if unknown
 * @param model        the model name/number, or {@code null} if unknown
 * @param registration a regulatory registration mark, or {@code null} if none
 */
public record IdentityRequest(String serialNumber, String make, String model, String registration) {

    /**
     * @return the domain {@link Identity} for these facts
     */
    public Identity toIdentity() {
        return new Identity(serialNumber, make, model, registration);
    }
}
