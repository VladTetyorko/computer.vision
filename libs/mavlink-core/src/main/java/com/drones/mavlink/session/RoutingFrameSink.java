package com.drones.mavlink.session;

import com.drones.mavlink.PeerId;
import com.drones.mavlink.codec.FrameSink;
import com.drones.mavlink.codec.FrameWriter;
import com.drones.mavlink.transport.LinkId;

import java.util.Objects;

/**
 * The {@link FrameSink} implementation that closes W1's routing gap (see {@code FrameWriter}'s own
 * javadoc and this module's API.md L2 "Routing lives in L3" note): resolves a {@link PeerId}'s link
 * through {@link PeerDirectory}, then delegates to {@link FrameWriter#broadcast}, which sends via
 * that link's own persistent {@code MavlinkConnection} (correct per-link sequence numbering).
 *
 * <h2>"You cannot send to a peer you have not heard from"</h2>
 * This is not a local safety choice — it is the protocol's own forwarding rule (plan §2.3): a
 * router only forwards a targeted message to a link a message from that target previously arrived
 * on. {@link #send} throws when {@code target} is not (yet) in {@link PeerDirectory} for exactly
 * that reason, naming the rule rather than leaving a caller to guess why the send failed.
 *
 * <h2>A known limitation this wave does not close</h2>
 * {@link FrameWriter#broadcast} always addresses that link's own {@link
 * com.drones.mavlink.transport.MavlinkLink#defaultTarget()} — for a {@code UdpListenLink}, "the most
 * recently learned sender," not necessarily {@code target}'s own address. When exactly one peer
 * shares a link this is correct by construction; when several peers share one listen link (the
 * multi-peer scenario this wave's own acceptance test exercises on the <i>receive</i> side),
 * {@link #send} can route to the right <i>link</i> but not guarantee the right <i>address</i> on
 * it unless {@code target} is also the most-recently-heard peer there. Closing this fully needs
 * {@code FrameWriter} (or a lower-level primitive) to accept an explicit {@code LinkPeer} target
 * instead of always trusting {@code defaultTarget()} — out of this wave's minimal codec-touch
 * mandate (only un-implementing {@code FrameSink} from {@code FrameWriter} was in scope). See this
 * module's MODULE.md for the fuller writeup; flagged as a W2 objection for W3/W4 to close.
 */
public final class RoutingFrameSink implements FrameSink {

    private final PeerDirectory peers;
    private final FrameWriter writer;

    public RoutingFrameSink(PeerDirectory peers, FrameWriter writer) {
        this.peers = Objects.requireNonNull(peers, "peers");
        this.writer = Objects.requireNonNull(writer, "writer");
    }

    @Override
    public void send(Object payload, PeerId target) {
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(target, "target");
        Peer peer = peers.peer(target);
        if (peer == null) {
            throw new IllegalArgumentException(
                    "Cannot send to peer " + target + " -- it has not been heard from on any link yet. "
                            + "MAVLink's own forwarding rule: a message is only ever routed to a link a "
                            + "message from that target previously arrived on (plan §2.3).");
        }
        // sendTo, not broadcast: on a shared listen link `defaultTarget()` is "whoever spoke most
        // recently", which for a targeted command on a fleet gateway can be the wrong aircraft.
        writer.sendTo(payload, peer.link(), peer.address());
    }

    @Override
    public void broadcast(Object payload, LinkId link) {
        writer.broadcast(payload, link);
    }
}
