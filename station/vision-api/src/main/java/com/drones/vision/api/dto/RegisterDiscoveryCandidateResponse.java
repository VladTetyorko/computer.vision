package com.drones.vision.api.dto;

import com.drones.vision.warehouse.domain.model.Asset;

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
 * @param assetId     the newly created asset's id, as a canonical UUID string
 * @param displayName the newly created asset's name
 * @param category    the newly created asset's category slug
 */
public record RegisterDiscoveryCandidateResponse(String assetId, String displayName, String category) {

    /**
     * Maps the {@link Asset} {@link com.drones.vision.warehouse.application.discovery.DiscoveryInboxService#register}
     * created to its wire representation.
     *
     * @param asset the created asset
     * @return the response body for {@code asset}
     */
    public static RegisterDiscoveryCandidateResponse from(Asset asset) {
        return new RegisterDiscoveryCandidateResponse(asset.id().value().toString(), asset.displayName(),
                asset.category().slug());
    }
}
