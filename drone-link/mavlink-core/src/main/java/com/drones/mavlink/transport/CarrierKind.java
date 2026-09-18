package com.drones.mavlink.transport;

/**
 * Which physical/logical technology a {@link MavlinkLink} rides on — the "how bytes move" half of
 * LINK-PAIRING-PLAN.md §1's three-word model (the other two, {@code Pairing}/{@code Link}, live
 * above this layer). Additive: a third carrier (e.g. a future ESP-NOW adapter) is a new constant
 * plus a new adapter module, never a rework of {@link LinkRegistry}/{@link LinkDescriptor}.
 */
public enum CarrierKind {
    UDP,
    SERIAL
}
