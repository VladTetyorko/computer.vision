package com.drones.mavlink.session;

import com.drones.mavlink.PeerId;
import com.drones.mavlink.transport.LinkId;

import java.util.Collection;
import java.util.List;

/**
 * Who is out there — read-only protocol facts, updated by {@link MavlinkSession} as frames arrive.
 * Deliberately carries no pin/claim/re-election logic: which sysid a {@code Device} owns is project
 * policy, not a protocol fact, and lives in the driving adapter (plan §3.4). This interface only
 * ever answers "what has been observed," never "who is allowed to use it."
 */
public interface PeerDirectory {

    /** Every peer currently known, in no particular order. */
    Collection<Peer> peers();

    /** The peer identified by {@code id}, or {@code null} if nothing has been heard from it yet. */
    Peer peer(PeerId id);

    /** Peers most recently heard on {@code link}. */
    List<Peer> peersOnLink(LinkId link);
}
