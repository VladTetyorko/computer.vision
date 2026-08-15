package com.drones.mavlink.session;

import com.drones.mavlink.SysId;

import java.util.Objects;

/**
 * The key a {@link Correlator} waiter is registered and matched under.
 *
 * @param system        the origin system of the expected reply — never the requester's own sysid,
 *                       and never {@code targetSystem}/{@code targetComponent} wire extension
 *                       fields, which are not reliably populated (plan §2.3)
 * @param messageId      the expected reply's wire message id
 * @param discriminator meaning is per-service: the command id for {@code COMMAND_ACK}, the item
 *                      seq for mission transfers (W6), the session/seq for FTP (W6)
 */
public record MatchKey(SysId system, int messageId, long discriminator) {

    public MatchKey {
        Objects.requireNonNull(system, "system");
    }
}
