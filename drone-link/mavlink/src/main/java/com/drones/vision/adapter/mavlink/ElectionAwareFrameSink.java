package com.drones.vision.adapter.mavlink;

import com.drones.mavlink.PeerId;
import com.drones.mavlink.codec.FrameSink;
import com.drones.mavlink.session.Peer;
import com.drones.mavlink.session.PeerDirectory;
import com.drones.mavlink.transport.LinkId;

import com.drones.vision.adapter.mavlink.election.LinkGroup;

import java.util.Objects;

/**
 * {@link FrameSink} decorator that steers control TX to a {@link LinkGroup}'s elected ACTIVE link
 * rather than {@code RoutingFrameSink}'s own raw "whichever link most recently delivered a frame
 * from this peer" rule (LINK-PAIRING-PLAN.md §4 row L3: "{@code RoutingFrameSink} writes control to
 * the group's ACTIVE link"). Delegates to {@code RoutingFrameSink} unchanged when the two already
 * agree — the common, healthy-single-link case — or when election has no opinion at all (a sysid
 * this gateway's {@link LinkGroupTracker} has never sighted, or one with no ACTIVE link right now).
 *
 * <h2>A known limitation this wave does not close</h2>
 * On genuine divergence — the group's ACTIVE link differs from {@link PeerDirectory}'s own
 * last-heard link for this peer, exactly what a soft-timeout demotion or a dwell-gated reclaim
 * produces while the peer is still (also) audible on its old link — this class falls back to
 * {@link FrameSink#broadcast}, which addresses the active link's own {@code defaultTarget()} rather
 * than this specific peer's address. That is correct when the active link carries this vehicle
 * alone (the common bench/serial/point-to-point shape) but, on a shared multi-vehicle listen link,
 * inherits the exact gap {@code RoutingFrameSink}'s own javadoc already documents ("A known
 * limitation this wave does not close") — closing it needs {@code FrameWriter} to accept an
 * explicit {@link com.drones.mavlink.transport.LinkPeer} target on a specific link, out of this
 * wave's scope (see this module's MODULE.md).
 */
final class ElectionAwareFrameSink implements FrameSink {

    private final FrameSink delegate;
    private final PeerDirectory peers;
    private final LinkGroupTracker tracker;

    ElectionAwareFrameSink(FrameSink delegate, PeerDirectory peers, LinkGroupTracker tracker) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        this.peers = Objects.requireNonNull(peers, "peers must not be null");
        this.tracker = Objects.requireNonNull(tracker, "tracker must not be null");
    }

    @Override
    public void send(Object payload, PeerId target) {
        Objects.requireNonNull(target, "target must not be null");
        LinkGroup group = tracker.groupFor(target.system().value());
        LinkId active = group == null ? null : group.activeLinkId();
        Peer peer = peers.peer(target);
        if (active == null || peer == null || active.equals(peer.link())) {
            delegate.send(payload, target);
            return;
        }
        delegate.broadcast(payload, active);
    }

    @Override
    public void broadcast(Object payload, LinkId link) {
        delegate.broadcast(payload, link);
    }
}
