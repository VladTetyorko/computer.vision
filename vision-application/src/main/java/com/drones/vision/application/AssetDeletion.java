package com.drones.vision.application;

import com.drones.vision.domain.model.AssetId;

/**
 * A record of what one soft delete did.
 *
 * <p>Returned rather than {@code void} so the caller can say "removed my-drone and its 2 sources;
 * 14 flights kept" instead of a bare confirmation — the user should learn both the reach of the
 * action and, just as importantly, that their history survived it.
 *
 * @param id             the deleted asset's id
 * @param displayName    the deleted asset's name, for the confirmation message
 * @param devicesDeleted how many of its devices were marked deleted alongside it
 * @param usagesRetained how many recorded usages were kept
 * @param streamsStopped how many running streams had to be stopped first
 */
public record AssetDeletion(AssetId id, String displayName, int devicesDeleted, int usagesRetained,
                             int streamsStopped) {

    public AssetDeletion {
        if (id == null) {
            throw new IllegalArgumentException("AssetDeletion id must not be null");
        }
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("AssetDeletion displayName must not be blank");
        }
        if (devicesDeleted < 0 || usagesRetained < 0 || streamsStopped < 0) {
            throw new IllegalArgumentException("AssetDeletion counts must not be negative");
        }
    }
}
