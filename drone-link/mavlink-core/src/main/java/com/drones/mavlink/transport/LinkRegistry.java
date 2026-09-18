package com.drones.mavlink.transport;

/**
 * The carrier-facing seam a carrier adapter (drone-link/carrier-udp, drone-link/carrier-serial)
 * uses to add or remove a {@link MavlinkLink} against whatever is actually consuming frames — in
 * this codebase, {@code MavlinkGateway} (drone-link/mavlink), which {@code implements
 * LinkRegistry} directly over its own {@code MavlinkSession} (LINK-PAIRING-PLAN.md §3.1).
 *
 * <p>{@code register} does not mint a new id — the returned {@link LinkId} is always {@code
 * link.id()} — and never opens or binds anything itself; the caller supplies an already-live
 * {@link MavlinkLink}. A carrier that later loses its link (a hotplug removal, a bench cable
 * unplugged) calls {@link #unregister} but is responsible for closing the link itself; the
 * registry does not own it, mirroring {@code MavlinkSession}'s own "does not own the link"
 * contract that both methods delegate to underneath.
 */
public interface LinkRegistry {

    /** Adds {@code link} under {@code descriptor}; returns {@code link.id()}. */
    LinkId register(MavlinkLink link, LinkDescriptor descriptor);

    /** Removes a previously-registered link. Idempotent; never closes the link. */
    void unregister(LinkId id);
}
