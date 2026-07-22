package com.drones.vision.api.dto;

import com.drones.vision.application.AssetDetails;
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
 * @param state              {@code ACTIVE} or {@code DEACTIVATED}; a separate axis from {@code status}
 * @param lastUsedAt         start time of the asset's most recent usage, or absent if never used
 * @param lastKnownPosition  last known position across usages, or absent if none is known
 * @param attributes         free-form key/value attributes
 * @param devices            the asset's resolved devices
 * @param recentUsages       the asset's recent usage history, newest first
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AssetDetailsResponse(String assetId, String displayName, String category, String categoryName,
                                    String owner, String status, String state, Instant lastUsedAt,
                                    GeoPositionResponse lastKnownPosition, Map<String, String> attributes,
                                    List<DeviceResponse> devices, List<AssetUsageResponse> recentUsages) {

    /**
     * Maps an {@link AssetDetails} read model to its wire representation.
     *
     * @param details the detail view to map
     * @return the response body for {@code details}
     */
    public static AssetDetailsResponse from(AssetDetails details) {
        AssetSummaryResponse summary = AssetSummaryResponse.from(details.summary());
        List<DeviceResponse> devices = details.devices().stream().map(DeviceResponse::from).toList();
        List<AssetUsageResponse> usages = details.recentUsages().stream().map(AssetUsageResponse::from).toList();
        return new AssetDetailsResponse(
                summary.assetId(),
                summary.displayName(),
                summary.category(),
                summary.categoryName(),
                summary.owner(),
                summary.status(),
                summary.state(),
                summary.lastUsedAt(),
                summary.lastKnownPosition(),
                summary.attributes(),
                devices,
                usages);
    }
}
