package com.drones.vision.flight.domain.model;

/**
 * Which physical/logical technology a link rides on — mirrored from {@code mavlink-core}'s {@code
 * com.drones.mavlink.transport.CarrierKind} (LINK-PAIRING-PLAN.md §3.4). See {@link LinkId}'s own
 * javadoc for why this module keeps a local copy rather than importing that type directly.
 */
public enum CarrierKind {
    UDP,
    SERIAL
}
