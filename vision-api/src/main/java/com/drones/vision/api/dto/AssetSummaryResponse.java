package com.drones.vision.api.dto;

import com.drones.vision.application.AssetSummary;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.Map;

/**
 * Response body element for {@code GET /api/assets}, and the shared field
 * set that {@link AssetDetailsResponse} extends with devices and usage
 * history.
 *
 * <p>{@code lastUsedAt} and {@code lastKnownPosition} are omitted from the
 * JSON entirely (rather than serialized as {@code null}) for an asset that
 * has never been used. {@code owner} is the asset's {@code Ownership}'s
 * {@code ownerId}, as a canonical UUID string — until the identity phase
 * (ARCHITECTURE.md §6) this is always the constant dev principal.
 *
 * @param assetId           asset identity, as a canonical UUID string
 * @param displayName       human-readable name (e.g. "my drone")
 * @param category           the asset's category slug
 * @param categoryName       human-readable name of the asset's category
 * @param owner              the owning user's id, as a canonical UUID string
 * @param status             {@code OFFLINE} or {@code STREAMING}, derived from active streams
 * @param lifecycle          {@code ACTIVE}, {@code DEACTIVATED}, or {@code DELETED} (docs/CYCLES-PLAN.md
 *                           §8's pinned contract); a separate axis from {@code status}, since "idle
 *                           right now" and "withdrawn from service" are different facts
 * @param lastUsedAt         start time of the asset's most recent usage, or absent if never used
 * @param lastKnownPosition  last known position across usages, or absent if none is known
 * @param attributes         free-form key/value attributes
 * @param hasImage           whether an image is stored for this asset (docs/UX-REWORK-PLAN.md
 *                           §U-d item 3, CONTRACT 2) — never the image bytes themselves, only
 *                           whether {@code GET /api/assets/{id}/image} would return one
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AssetSummaryResponse(String assetId, String displayName, String category, String categoryName,
                                    String owner, String status, String lifecycle, Instant lastUsedAt,
                                    GeoPositionResponse lastKnownPosition, Map<String, String> attributes,
                                    boolean hasImage) {

    /**
     * Maps an {@link AssetSummary} read model to its wire representation.
     *
     * @param summary  the summary to map
     * @param hasImage whether an image is stored for this asset (a separate lookup — {@link
     *                 AssetSummary} carries no notion of one; see {@code
     *                 com.drones.vision.api.AssetController})
     * @return the response body element for {@code summary}
     */
    public static AssetSummaryResponse from(AssetSummary summary, boolean hasImage) {
        return new AssetSummaryResponse(
                summary.asset().id().value().toString(),
                summary.asset().displayName(),
                summary.asset().category().slug(),
                summary.categoryName(),
                summary.asset().ownership().ownerId().value().toString(),
                summary.status().name(),
                summary.asset().state().name(),
                summary.lastUsedAt(),
                GeoPositionResponse.from(summary.lastKnownPosition()),
                summary.asset().attributes(),
                hasImage);
    }
}
