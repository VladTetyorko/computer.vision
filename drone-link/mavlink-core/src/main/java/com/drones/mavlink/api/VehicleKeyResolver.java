package com.drones.mavlink.api;

import com.drones.mavlink.PeerId;

/**
 * Maps a {@link CommandRequest#vehicleKey()} to the {@link PeerId} it currently means. This module
 * cannot know what a vehicle key means on its own (a device id, an asset id, a fleet slug, ...) —
 * that mapping is entirely host-application policy, supplied here so {@code com.drones.mavlink.api}
 * stays domain-free while still being routable (plan §5.1).
 */
public interface VehicleKeyResolver {

    /** @return the peer {@code vehicleKey} currently resolves to, or {@code null} if unknown. */
    PeerId resolve(String vehicleKey);
}
