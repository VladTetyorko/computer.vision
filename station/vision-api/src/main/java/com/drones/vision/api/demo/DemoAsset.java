package com.drones.vision.api.demo;

import com.drones.vision.kernel.AssetId;

/**
 * One asset the demo created, carried between {@link DemoFleet}'s create and start-stream passes
 * and on into {@link DemoScenario}'s assignment pass.
 *
 * @param id          the created asset
 * @param displayName its call sign, as shown in every list in the UI
 * @param videoName   file name of the video backing it, or {@code null} for a fully synthetic asset
 */
public record DemoAsset(AssetId id, String displayName, String videoName) {

    public DemoAsset {
        if (id == null) {
            throw new IllegalArgumentException("DemoAsset id must not be null");
        }
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("DemoAsset displayName must not be blank");
        }
    }
}
