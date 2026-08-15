package com.drones.mavlink;

/**
 * A MAVLink system id — one physical vehicle or ground station on the network. Valid range is
 * 1..255 per the wire format (a single byte, with 0 reserved/unused by the protocol); there is no
 * broadcast sentinel here — {@code target_system == 0} is a payload-level convention some messages
 * use, not a value this type accepts, since {@code SysId} always names a real, specific origin.
 *
 * <p>Identity in MAVLink is {@code (SysId, CompId)}, never a transport address: one link can carry
 * several components, and NAT can move the observed address between packets from the same system.
 */
public record SysId(int value) {

    public SysId {
        if (value < 1 || value > 255) {
            throw new IllegalArgumentException("SysId must be 1..255, got " + value);
        }
    }
}
