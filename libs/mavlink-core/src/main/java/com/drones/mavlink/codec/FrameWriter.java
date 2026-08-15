package com.drones.mavlink.codec;

import com.drones.mavlink.CompId;
import com.drones.mavlink.PeerId;
import com.drones.mavlink.SysId;
import com.drones.mavlink.transport.LinkId;
import com.drones.mavlink.transport.LinkPeer;
import com.drones.mavlink.transport.MavlinkLink;

import io.dronefleet.mavlink.MavlinkConnection;
import io.dronefleet.mavlink.annotations.MavlinkMessageInfo;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The <b>only</b> class in this module permitted to construct an
 * {@code io.dronefleet.mavlink.MavlinkConnection} — every other class reaches the wire through
 * {@link MavlinkLink}/{@link FrameReader}/this class, never the library directly. This is the
 * one-place-library-swap seam the whole wave exists to create: five call sites in
 * {@code adapter-mavlink} build a connection by hand today (see this module's own MODULE.md), and
 * a future library swap (plan §2.4) only has to change this one file.
 *
 * <h2>Sequence numbering</h2>
 * "Owned per {@code (link, our sysid, our compid)}, wrapping at 255" is satisfied by construction
 * rather than by hand: this class keeps exactly one {@code MavlinkConnection} per registered link,
 * built once in {@link #addLink} and reused for every subsequent send on that link. A
 * {@code MavlinkConnection}'s own sequence counter already increments correctly and wraps (the
 * wire byte is naturally truncated to 8 bits during serialization) — since {@code ourSystem}/
 * {@code ourComponent} are fixed for this {@code FrameWriter}'s whole life, "per link" is the only
 * axis that actually varies, and one connection per link is exactly that.
 *
 * <h2>Routing lives in L3, not here</h2>
 * This class deliberately no longer implements {@link FrameSink} (it did in W1 — see this module's
 * MODULE.md for that history). It cannot answer "which link is this {@link PeerId} reachable on";
 * that needs an L3 {@code PeerDirectory}, which does not exist at this level. {@code
 * com.drones.mavlink.session.RoutingFrameSink} (L3) is the {@code FrameSink} implementation now:
 * it resolves a peer's link via {@code PeerDirectory} and delegates to {@link #broadcast} here.
 * {@link #send} and {@link #broadcast} stay as plain public methods — {@link #send} still only
 * works with exactly one registered link (throwing otherwise, see its own javadoc), which remains
 * useful for a caller that genuinely has one link and no need to build a {@code PeerDirectory} at
 * all (e.g. a TX simulator, or a test); {@link #broadcast} is unaffected either way, since the
 * caller already names the link.
 */
public final class FrameWriter {

    private final SysId ourSystem;
    private final CompId ourComponent;
    private final Map<LinkId, LinkConnection> links = new ConcurrentHashMap<>();

    public FrameWriter(SysId ourSystem, CompId ourComponent) {
        this.ourSystem = Objects.requireNonNull(ourSystem, "ourSystem");
        this.ourComponent = Objects.requireNonNull(ourComponent, "ourComponent");
    }

    /** Registers {@code link} for sending, building its dedicated {@code MavlinkConnection} once. */
    public void addLink(MavlinkLink link) {
        Objects.requireNonNull(link, "link");
        links.put(link.id(), new LinkConnection(link));
    }

    /** Unregisters a link. Idempotent. Does not close the link itself — the caller owns that. */
    public void removeLink(LinkId id) {
        Objects.requireNonNull(id, "id");
        links.remove(id);
    }

    /**
     * Sends {@code payload} addressed to {@code target}, requiring exactly one registered link —
     * see this class's own javadoc ("Routing lives in L3, not here") for why. Prefer {@code
     * com.drones.mavlink.session.RoutingFrameSink} when more than one link may be registered.
     *
     * @throws IllegalStateException if the registered link count is not exactly one
     */
    public void send(Object payload, PeerId target) {
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(target, "target");
        soleRegisteredLink().send(payload, null);
    }

    /**
     * Sends {@code payload} out {@code link} unconditionally, using that link's own
     * {@link MavlinkLink#defaultTarget()} — matches the protocol's own broadcast dispatch path
     * (spec: {@code target_system == 0} is an unconditional forward, no learned-route check).
     */
    public void broadcast(Object payload, LinkId link) {
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(link, "link");
        connectionFor(link).send(payload, null);
    }

    /**
     * Sends {@code payload} out {@code link} to one explicit {@code address}, bypassing
     * {@link MavlinkLink#defaultTarget()} entirely.
     *
     * <p>This is the only correct primitive for a <b>targeted</b> message on a shared listen link.
     * {@code defaultTarget()} is "whoever transmitted most recently", so on a fleet gateway — several
     * vehicles pushing to one bound port, this platform's normal production shape — a targeted send
     * that trusted it would deliver to whichever aircraft happened to speak last rather than to the
     * one addressed. For an {@code ARM} or a mode change that is not a routing inaccuracy, it is the
     * wrong aircraft. {@code com.drones.mavlink.session.RoutingFrameSink} resolves the intended
     * peer's own last-known address from {@code PeerDirectory} and passes it here.
     */
    public void sendTo(Object payload, LinkId link, LinkPeer address) {
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(link, "link");
        Objects.requireNonNull(address, "address");
        connectionFor(link).send(payload, address);
    }

    private LinkConnection connectionFor(LinkId link) {
        LinkConnection connection = links.get(link);
        if (connection == null) {
            throw new IllegalArgumentException("No link registered with id " + link);
        }
        return connection;
    }

    private LinkConnection soleRegisteredLink() {
        if (links.size() != 1) {
            throw new IllegalStateException(
                    "FrameWriter.send(payload, PeerId) needs exactly one registered link (it has no "
                            + "PeerDirectory to resolve which link a peer is reachable on -- see "
                            + "com.drones.mavlink.session.RoutingFrameSink for that); "
                            + "registered link count = " + links.size()
                            + ". Use broadcast(payload, LinkId) to target a specific link explicitly.");
        }
        return links.values().iterator().next();
    }

    /**
     * One link's persistent {@link MavlinkConnection} — persistent specifically so the library's own
     * sequence counter keeps incrementing across sends; a fresh connection per send would reset
     * {@code seq} to 0 every time and make packet-loss accounting on the far side meaningless.
     *
     * <p>{@link #send} is {@code synchronized} because {@code MavlinkConnection#send2} performs a
     * non-atomic {@code write(...)} then {@code flush()} against one shared buffer, and several
     * services legitimately share one link concurrently — a heartbeat at 1 Hz, an RC override stream
     * at up to 50 Hz and a command send can all be in flight at once. Without the monitor their
     * bytes would interleave inside a single datagram. The monitor also makes the per-send target
     * override below safe: the address is set, used and cleared entirely within one critical section.
     */
    private final class LinkConnection {

        private final MavlinkLink link;
        private final LinkOutputStream out;
        private final MavlinkConnection connection;

        LinkConnection(MavlinkLink link) {
            this.link = link;
            this.out = new LinkOutputStream(link);
            this.connection = MavlinkConnection.create(InputStream.nullInputStream(), out);
        }

        synchronized void send(Object payload, LinkPeer address) {
            if (payload.getClass().getAnnotation(MavlinkMessageInfo.class) == null) {
                throw new IllegalArgumentException(
                        "payload " + payload.getClass().getName()
                                + " has no @MavlinkMessageInfo -- not a MAVLink message payload");
            }
            out.target(address);
            try {
                connection.send2(ourSystem.value(), ourComponent.value(), payload);
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to send a MAVLink frame on link " + link.id(), e);
            } finally {
                out.target(null);
            }
        }
    }

    /**
     * Bridges {@code MavlinkConnection}'s blocking-write {@link OutputStream} expectation to
     * {@link MavlinkLink#send}. Buffers across {@code write} calls and sends exactly one
     * {@link MavlinkLink#send} per {@code flush()} — {@code MavlinkConnection#send1/send2} always
     * call {@code write(...)} then {@code flush()} exactly once per outgoing message, producing
     * one physical send per MAVLink message, matching how a real telemetry radio pushes MAVLink.
     * Destination resolution at flush time: an explicit {@link #target(LinkPeer)} set for this one
     * send wins; otherwise {@link MavlinkLink#defaultTarget()}, so a broadcast on a listen link uses
     * that link's freshest learned peer rather than a stale one captured at construction.
     */
    private static final class LinkOutputStream extends OutputStream {

        private final MavlinkLink link;
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private LinkPeer target;

        LinkOutputStream(MavlinkLink link) {
            this.link = link;
        }

        /** Set (or cleared, with {@code null}) by the caller's monitor in {@link LinkConnection#send}. */
        void target(LinkPeer target) {
            this.target = target;
        }

        @Override
        public void write(int b) {
            buffer.write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) {
            buffer.write(b, off, len);
        }

        @Override
        public void flush() throws IOException {
            if (buffer.size() == 0) {
                return;
            }
            byte[] data = buffer.toByteArray();
            buffer.reset();
            link.send(data, 0, data.length, target != null ? target : link.defaultTarget());
        }
    }
}
