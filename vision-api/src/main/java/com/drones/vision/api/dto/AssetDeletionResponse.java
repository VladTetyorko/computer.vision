package com.drones.vision.api.dto;

import com.drones.vision.warehouse.application.asset.AssetDeletion;

/**
 * Response body for {@code DELETE /api/assets/{id}} (docs/main/CYCLES-PLAN.md §8's pinned contract).
 *
 * <p>Returned instead of an empty {@code 204} so the caller can tell the user both what the
 * deletion reached — "removed my-drone and its 2 sources" — and, just as importantly, what it
 * preserved: "14 flights kept". The deletion is soft, and the confirmation should say so rather
 * than leaving the user to assume their history is gone.
 *
 * @param assetId        the deleted asset's id, as a canonical UUID string
 * @param displayName    the deleted asset's name
 * @param devicesDeleted how many of its devices were marked deleted alongside it
 * @param usagesRetained how many recorded usages were kept
 * @param streamsStopped how many running streams had to be stopped first
 */
public record AssetDeletionResponse(String assetId, String displayName, int devicesDeleted, int usagesRetained,
                                     int streamsStopped) {

    /**
     * Maps an {@link AssetDeletion} record to its wire representation.
     *
     * @param deletion the deletion record to map
     * @return the response body for {@code deletion}
     */
    public static AssetDeletionResponse from(AssetDeletion deletion) {
        return new AssetDeletionResponse(
                deletion.id().value().toString(),
                deletion.displayName(),
                deletion.devicesDeleted(),
                deletion.usagesRetained(),
                deletion.streamsStopped());
    }
}
