package com.drones.mavlink.codec;

import com.drones.mavlink.PeerId;
import com.drones.mavlink.transport.LinkId;

/**
 * Frames out. Sequence numbering, our own sysid/compid stamping, and framing are all handled by
 * the implementation ({@link FrameWriter}) — callers hand over a library message object and where
 * it should go.
 */
public interface FrameSink {

    /**
     * Sends {@code payload} addressed to {@code target}.
     *
     * @param payload a {@code io.dronefleet.mavlink} generated message object (e.g. a
     *                {@code CommandLong}) — see {@link FrameWriter}'s javadoc for the exact
     *                validation applied
     * @param target  who this is for. See {@link FrameWriter}'s javadoc for the W1 routing
     *                limitation: without an L3 {@code PeerDirectory} yet, a {@code FrameWriter}
     *                with more than one registered link cannot resolve which link a peer is
     *                reachable on.
     */
    void send(Object payload, PeerId target);

    /**
     * Sends {@code payload} out {@code link} unconditionally, using that link's own
     * {@link com.drones.mavlink.transport.MavlinkLink#defaultTarget()} — matches the protocol's
     * own broadcast dispatch path (spec: {@code target_system == 0} is an unconditional forward,
     * no learned-route check).
     */
    void broadcast(Object payload, LinkId link);
}
