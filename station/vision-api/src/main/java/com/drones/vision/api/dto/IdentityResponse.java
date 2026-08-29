package com.drones.vision.api.dto;

import com.drones.vision.warehouse.domain.model.Identity;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Wire shape for an asset's {@link Identity} facts (docs/plans/active/WAREHOUSE-UX-PLAN.md D1) —
 * embedded in {@link AssetSummaryResponse}/{@link AssetDetailsResponse} as {@code identity}.
 *
 * <p>Always present as an object (an asset always carries an {@link Identity}, even if it is
 * {@link Identity#NONE}); individual fields are omitted, not {@code null}-valued, when unknown.
 *
 * @param serialNumber the manufacturer's serial number, or absent if unknown
 * @param make         the manufacturer, or absent if unknown
 * @param model        the model name/number, or absent if unknown
 * @param registration a regulatory registration mark, or absent if none
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record IdentityResponse(String serialNumber, String make, String model, String registration) {

    /**
     * Maps a domain {@link Identity} to its wire representation.
     *
     * @param identity the identity to map; never {@code null}
     * @return the response fragment for {@code identity}
     */
    public static IdentityResponse from(Identity identity) {
        return new IdentityResponse(identity.serialNumber(), identity.make(), identity.model(),
                identity.registration());
    }
}
