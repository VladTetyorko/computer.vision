package com.drones.vision.simulation.application;

import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.StreamId;

/**
 * What {@link SimulationService#simulate} produced: the newly created asset, and — when
 * {@link SimulationSpec#autoStart()} was requested — the stream it started.
 *
 * @param assetId  the created asset's identity
 * @param streamId the started stream's identity, or {@code null} when {@code autoStart} was false
 */
public record SimulatedAsset(AssetId assetId, StreamId streamId) {

    public SimulatedAsset {
        if (assetId == null) {
            throw new IllegalArgumentException("SimulatedAsset assetId must not be null");
        }
    }
}
