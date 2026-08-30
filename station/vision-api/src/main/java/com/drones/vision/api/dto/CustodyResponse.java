package com.drones.vision.api.dto;

import com.drones.vision.warehouse.domain.model.Custody;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * Wire shape for an asset's {@link Custody} (docs/plans/active/WAREHOUSE-UX-PLAN.md D2) — embedded
 * in {@link AssetSummaryResponse}/{@link AssetDetailsResponse} as {@code custody}.
 *
 * <p>Always present as an object (an asset always carries a {@link Custody}, even if it is
 * {@link Custody#NONE} — in stock, held by nobody); {@code custodianId}/{@code since} are omitted,
 * not {@code null}-valued, when the asset is in stock.
 *
 * @param custodianId the current custodian's id, as a canonical UUID string, or absent if in stock
 * @param location    a free-form note of where the asset is, or absent if none is recorded
 * @param since       when the current custodian took custody, or absent if in stock
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CustodyResponse(String custodianId, String location, Instant since) {

    /**
     * Maps a domain {@link Custody} to its wire representation.
     *
     * @param custody the custody to map; never {@code null}
     * @return the response fragment for {@code custody}
     */
    public static CustodyResponse from(Custody custody) {
        String custodianId = custody.custodianId() == null ? null : custody.custodianId().value().toString();
        return new CustodyResponse(custodianId, custody.location(), custody.since());
    }
}
