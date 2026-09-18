package com.drones.vision.api.dto;

import com.drones.vision.warehouse.domain.model.Asset;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Response body for {@code POST /api/discovery/inbox/{id}/register} (docs/plans/active/
 * ZERO-CONFIG-ONBOARDING-CONTEXT.md &sect;11, Z2c) — a minimal pointer at the asset the candidate
 * became, not the full {@code AssetDetailsResponse} shape ({@code AssetController#details}'s own
 * response): building that here would pull in the same {@code AssetImageRepositoryPort}/{@code
 * AssetRowFacts} join collaborators {@code AssetController} already carries, past the
 * five-parameter constructor ceiling for a controller whose primary job is the inbox, not asset
 * detail rendering. A client that wants the full detail view already knows how to call {@code GET
 * /api/assets/{assetId}} with the id this response gives it.
 *
 * @param assetId            the newly created asset's id, as a canonical UUID string
 * @param displayName        the newly created asset's name
 * @param category           the newly created asset's category slug
 * @param sysidPushRequired  "adopt is one motion" (docs/plans/active/LINK-PAIRING-PLAN.md §7 ruling
 *                           3): {@code true} when the new device's assigned sysid differs from the
 *                           one it was heard announcing, so the confirm screen must show the
 *                           {@code MAV_SYSID} push step; absent (never serialized) when the new
 *                           device is not a {@code mavlink} TELEMETRY device and pairing was not
 *                           attempted at all
 * @param assignedSysid      the sysid {@link com.drones.vision.warehouse.application.pairing.PairingService#pair}
 *                           actually assigned, present exactly when {@link #sysidPushRequired} is
 *                           {@code true} — field name/shape frozen by {@code station/vision-web}'s
 *                           {@code core/api/models.ts} L4 mirror, so the confirm screen can show
 *                           which sysid to push without a follow-up read
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RegisterDiscoveryCandidateResponse(String assetId, String displayName, String category,
                                                   Boolean sysidPushRequired, Integer assignedSysid) {

    /**
     * Maps the {@link Asset} {@link com.drones.vision.warehouse.application.discovery.DiscoveryInboxService#register}
     * created to its wire representation, with no pairing outcome to report.
     *
     * @param asset the created asset
     * @return the response body for {@code asset}
     */
    public static RegisterDiscoveryCandidateResponse from(Asset asset) {
        return from(asset, null, null);
    }

    /**
     * Maps the {@link Asset} {@link com.drones.vision.warehouse.application.discovery.DiscoveryInboxService#register}
     * created to its wire representation.
     *
     * @param asset             the created asset
     * @param sysidPushRequired see {@link #sysidPushRequired()}; {@code null} when pairing was not attempted
     * @param assignedSysid     see {@link #assignedSysid()}; {@code null} unless {@code sysidPushRequired} is {@code true}
     * @return the response body for {@code asset}
     */
    public static RegisterDiscoveryCandidateResponse from(Asset asset, Boolean sysidPushRequired,
                                                            Integer assignedSysid) {
        return new RegisterDiscoveryCandidateResponse(asset.id().value().toString(), asset.displayName(),
                asset.category().slug(), sysidPushRequired, assignedSysid);
    }
}
