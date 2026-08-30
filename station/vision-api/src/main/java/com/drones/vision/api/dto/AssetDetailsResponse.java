package com.drones.vision.api.dto;

import com.drones.vision.warehouse.application.asset.AssetDetails;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Response body for {@code POST /api/assets} and {@code GET
 * /api/assets/{id}}.
 *
 * <p>Extends {@link AssetSummaryResponse}'s field set with the asset's
 * resolved devices and recent usage history; see that record's javadoc for
 * the shared fields' semantics (including the {@code NON_NULL} omission
 * convention).
 *
 * @param assetId           asset identity, as a canonical UUID string
 * @param displayName       human-readable name (e.g. "my drone")
 * @param category           the asset's category slug
 * @param categoryName       human-readable name of the asset's category
 * @param owner              the owning user's id, as a canonical UUID string
 * @param status             {@code OFFLINE} or {@code STREAMING}, derived from active streams
 * @param lifecycle          {@code ACTIVE}, {@code DEACTIVATED}, or {@code DELETED} (docs/main/CYCLES-PLAN.md
 *                           §8's pinned contract); a separate axis from {@code status}
 * @param lastUsedAt         start time of the asset's most recent usage, or absent if never used
 * @param lastKnownPosition  last known position across usages, or absent if none is known
 * @param attributes         free-form key/value attributes
 * @param hasImage           whether an image is stored for this asset (docs/plans/done/UX-REWORK-PLAN.md
 *                           §U-d item 3, CONTRACT 2)
 * @param identity           serial/make/model/registration facts (docs/plans/active/WAREHOUSE-UX-PLAN.md D1)
 * @param custody            who currently holds this asset, or in-stock if nobody (D2)
 * @param inventoryState     this asset's <b>effective</b> inventory state (D6) — see {@link
 *                           AssetSummaryResponse#inventoryState()}
 * @param createdAt          when this asset was first registered
 * @param updatedAt          when this asset was last changed
 * @param firmware           the asset's most recently observed firmware, or absent — see {@link
 *                           AssetSummaryResponse#firmware()}
 * @param totalFlightSeconds cumulative flight seconds, or absent — see {@link
 *                           AssetSummaryResponse#totalFlightSeconds()}
 * @param devices            the asset's resolved devices
 * @param recentUsages       the asset's recent usage history, newest first
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AssetDetailsResponse(String assetId, String displayName, String category, String categoryName,
                                    String owner, String status, String lifecycle, Instant lastUsedAt,
                                    GeoPositionResponse lastKnownPosition, Map<String, String> attributes,
                                    boolean hasImage, IdentityResponse identity, CustodyResponse custody,
                                    String inventoryState, Instant createdAt, Instant updatedAt,
                                    FirmwareResponse firmware, Long totalFlightSeconds,
                                    List<DeviceResponse> devices, List<AssetUsageResponse> recentUsages) {

    /**
     * Maps an {@link AssetDetails} read model to its wire representation.
     *
     * @param details            the detail view to map
     * @param hasImage           whether an image is stored for this asset (a separate lookup — see
     *                           {@link AssetSummaryResponse#from})
     * @param firmware           the joined firmware fact, or {@code null} if the caller has none to
     *                           offer — see {@link AssetSummaryResponse#firmware()}
     * @param totalFlightSeconds the joined flight-hours fact, or {@code null} if the caller has none
     *                           to offer — see {@link AssetSummaryResponse#totalFlightSeconds()}
     * @return the response body for {@code details}
     */
    public static AssetDetailsResponse from(AssetDetails details, boolean hasImage, FirmwareResponse firmware,
                                             Long totalFlightSeconds) {
        AssetSummaryResponse summary = AssetSummaryResponse.from(details.summary(), hasImage, firmware,
                totalFlightSeconds);
        List<DeviceResponse> devices = details.devices().stream().map(DeviceResponse::from).toList();
        List<AssetUsageResponse> usages = details.recentUsages().stream().map(AssetUsageResponse::from).toList();
        return new AssetDetailsResponse(
                summary.assetId(),
                summary.displayName(),
                summary.category(),
                summary.categoryName(),
                summary.owner(),
                summary.status(),
                summary.lifecycle(),
                summary.lastUsedAt(),
                summary.lastKnownPosition(),
                summary.attributes(),
                summary.hasImage(),
                summary.identity(),
                summary.custody(),
                summary.inventoryState(),
                summary.createdAt(),
                summary.updatedAt(),
                summary.firmware(),
                summary.totalFlightSeconds(),
                devices,
                usages);
    }
}
