package com.drones.mavlink.codec;

import com.drones.mavlink.CompId;
import com.drones.mavlink.SysId;

import java.util.Objects;

/**
 * The wire-level facts of one MAVLink frame, decoded but not interpreted. {@code version} is 1 or
 * 2 (the magic-byte discriminator, translated to the number everyone actually means); the MAVLink
 * spec itself defines no formula for {@code sequence} beyond "wraps at 255, used to detect loss" —
 * drop-rate accounting on top of it is an L3 concern.
 *
 * <p>{@code incompatFlags}/{@code compatFlags} are always {@code 0} for a version-1 frame (v1 has
 * no such fields). Per spec, an unrecognized bit in {@code incompatFlags} must cause the whole
 * frame to be discarded — the underlying library already enforces this during decode, so a
 * {@code MavHeader} that exists at all has already passed that check.
 */
public record MavHeader(int version, int sequence, SysId system, CompId component, int messageId,
                         int incompatFlags, int compatFlags, boolean signed) {

    public MavHeader {
        if (version != 1 && version != 2) {
            throw new IllegalArgumentException("MAVLink version must be 1 or 2, got " + version);
        }
        if (sequence < 0 || sequence > 255) {
            throw new IllegalArgumentException("sequence must be 0..255, got " + sequence);
        }
        Objects.requireNonNull(system, "system");
        Objects.requireNonNull(component, "component");
        if (messageId < 0) {
            throw new IllegalArgumentException("messageId must be >= 0, got " + messageId);
        }
    }
}
