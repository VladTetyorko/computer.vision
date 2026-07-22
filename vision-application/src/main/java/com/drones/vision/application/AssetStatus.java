package com.drones.vision.application;

/**
 * Whether an asset is streaming right now.
 *
 * <p>A separate axis from {@link com.drones.vision.domain.model.LifecycleState}: "idle at the
 * moment" and "withdrawn from service" are different facts, and collapsing them would make a
 * deactivated asset indistinguishable from one that simply is not flying.
 */
public enum AssetStatus {

    /** No device of this asset has an active stream. */
    OFFLINE,

    /** At least one device of this asset is streaming. */
    STREAMING
}
