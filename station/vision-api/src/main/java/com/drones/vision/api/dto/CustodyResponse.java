package com.drones.vision.api.dto;

import com.drones.vision.warehouse.domain.model.Custody;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * Wire shape for an asset's {@link Custody} (docs/plans/active/WAREHOUSE-UX-PLAN.md D2) — embedded
 * in {@link AssetSummaryResponse}/{@link AssetDetailsResponse} as {@code custody}.
 *
 * <p>Always present as an object (an asset always carries a {@link Custody}, even if it is
 * {@link Custody#NONE} — in stock, held by nobody); {@code custodianId}/{@code custodianName}/{@code
 * since} are omitted, not {@code null}-valued, when the asset is in stock.
 *
 * <p><strong>{@code custodianName} is resolved server-side</strong>
 * (docs/plans/active/INVENTORY-REWORK-PLAN.md D3). The web used to join the id against {@code GET
 * /api/users}, which answers an <em>empty list</em> for a pilot's {@code ASSIGNED_ASSETS} scope — so
 * exactly the caller who most needs the name read a raw UUID instead
 * (docs/plans/active/INVENTORY-REWORK-CONTEXT.md §3, defect C). The server-side resolution is a
 * <em>label lookup by id</em>, never a listing: it tells a caller the name of the one person already
 * named on a row they can see, and widens nothing about who they may enumerate.
 *
 * @param custodianId   the current custodian's id, as a canonical UUID string, or absent if in stock
 * @param custodianName the current custodian's display name, or absent when the asset is in stock,
 *                      when the user record has since been removed, or when the responding endpoint
 *                      has no name lookup to offer (see {@link AssetSummaryResponse#from})
 * @param location      a free-form note of where the asset is, or absent if none is recorded
 * @param since         when the current custodian took custody, or absent if in stock
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CustodyResponse(String custodianId, String custodianName, String location, Instant since) {

    /**
     * Maps a domain {@link Custody} to its wire representation.
     *
     * @param custody       the custody to map; never {@code null}
     * @param custodianName the custodian's resolved display name, or {@code null} when unknown or
     *                      unresolvable — see this record's own javadoc
     * @return the response fragment for {@code custody}
     */
    public static CustodyResponse from(Custody custody, String custodianName) {
        String custodianId = custody.custodianId() == null ? null : custody.custodianId().value().toString();
        return new CustodyResponse(custodianId, custodianId == null ? null : custodianName, custody.location(),
                custody.since());
    }
}
