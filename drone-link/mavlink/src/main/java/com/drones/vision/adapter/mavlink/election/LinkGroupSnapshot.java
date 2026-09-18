package com.drones.vision.adapter.mavlink.election;

import com.drones.mavlink.transport.LinkId;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * A point-in-time view of one sysid's {@link LinkGroup} — every link that has ever delivered a
 * frame for it on the shared gateway, which one (if any) is ACTIVE, and whether an operator pin is
 * in effect (LINK-PAIRING-PLAN.md §3.4).
 *
 * @param sysid          the MAVLink system id this group tracks
 * @param links          every member link, in no particular order
 * @param activeLinkId   the group's current ACTIVE link, or {@code null} if none (hard timeout with
 *                       no healthy alternative)
 * @param pinned         {@code true} iff an operator pin is currently in effect
 * @param lastFailoverAt when the ACTIVE link last changed via <b>automatic</b> election — never
 *                       updated by an operator pin/release, matching the web contract's own "most
 *                       recent automatic failover" doc comment; {@code null} if it has never changed
 */
public record LinkGroupSnapshot(int sysid, List<LinkSnapshot> links, LinkId activeLinkId, boolean pinned,
                                 Instant lastFailoverAt) {

    public LinkGroupSnapshot {
        Objects.requireNonNull(links, "links must not be null");
        links = List.copyOf(links);
        // activeLinkId/lastFailoverAt are deliberately nullable -- see class javadoc.
    }
}
