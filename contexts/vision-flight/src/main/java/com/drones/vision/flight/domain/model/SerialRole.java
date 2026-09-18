package com.drones.vision.flight.domain.model;

/**
 * What a {@link CarrierKind#SERIAL} link is for — mirrored from {@code mavlink-core}'s {@code
 * com.drones.mavlink.transport.SerialRole} (LINK-PAIRING-PLAN.md §3.4). See {@link LinkId}'s own
 * javadoc for why this module keeps a local copy rather than importing that type directly.
 */
public enum SerialRole {
    /** Not a serial link (paired with {@link CarrierKind#UDP}). */
    NONE,
    /** A ground radio — eligible for auto-election. */
    GROUND_RADIO,
    /** A bench/USB debug cable — never auto-elected, reachable only via an operator pin. */
    BENCH
}
