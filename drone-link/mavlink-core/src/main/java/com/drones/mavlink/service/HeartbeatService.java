package com.drones.mavlink.service;

import com.drones.mavlink.PeerId;
import com.drones.mavlink.codec.FrameSink;
import com.drones.mavlink.config.MavlinkCoreSettings;
import com.drones.mavlink.session.Peer;
import com.drones.mavlink.session.PeerDirectory;
import com.drones.mavlink.session.TxScheduler;
import com.drones.mavlink.transport.LinkId;

import io.dronefleet.mavlink.minimal.Heartbeat;

import java.lang.System.Logger.Level;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MAVLink's heartbeat microservice (plan §2.2, Family B — streaming): emits our own {@code HEARTBEAT}
 * at a fixed rate, and tracks each known peer's connected/disconnected state against
 * {@code peerTimeout} (convention "4-5 missed heartbeats" — not a spec number, plan §2.3).
 *
 * <h2>Which links get our heartbeat</h2>
 * Built from {@link PeerDirectory} + {@link FrameSink} only (the L4 role-interface catalogue, plan
 * §3.3/§5.1) — there is no "list every registered link" seam at this level. Each tick derives its
 * broadcast targets from the distinct {@link Peer#link()} values currently known to
 * {@link PeerDirectory}: this platform's heartbeat reaches every link something has already been
 * heard on. A dial-out link with a fixed destination that has never sent us anything is not reachable
 * this way — see this module's MODULE.md for why that is an accepted, documented gap rather than an
 * oversight (and, for a listen-style link, is not a regression: {@code MavlinkLink#send} cannot
 * address an unlearned target at all, so nothing was reachable there either).
 *
 * <h2>Connectivity tracking is this class's own, not {@code LinkHealth}'s</h2>
 * {@code LinkHealth} is deliberately not one of L4's small role interfaces (plan §3.3's four:
 * {@code FrameSink}, {@code Correlator}, {@code PeerDirectory}, {@code TxScheduler}) — it stays a
 * session-level protocol-accounting concern. This class computes its own connected/disconnected view
 * straight from {@link Peer#lastHeard()} vs. {@code peerTimeout}, independently, on every tick.
 */
public final class HeartbeatService {

    private static final System.Logger LOG = System.getLogger(HeartbeatService.class.getName());

    private static final String TASK_NAME = "mavlink-heartbeat";

    private final FrameSink sink;
    private final TxScheduler scheduler;
    private final PeerDirectory peers;
    private final Duration heartbeatPeriod;
    private final Duration peerTimeout;
    private final HeartbeatContent content;
    private final Map<PeerId, Boolean> connectivity = new ConcurrentHashMap<>();
    private volatile TxScheduler.Handle handle;

    public HeartbeatService(FrameSink sink, TxScheduler scheduler, PeerDirectory peers, MavlinkCoreSettings settings) {
        this(sink, scheduler, peers, settings, HeartbeatContent.groundStation());
    }

    /** Like the 4-arg constructor, but announcing something other than the default GCS identity. */
    public HeartbeatService(FrameSink sink, TxScheduler scheduler, PeerDirectory peers, MavlinkCoreSettings settings,
                             HeartbeatContent content) {
        this(sink, scheduler, peers, Objects.requireNonNull(settings, "settings").heartbeatPeriod(),
                settings.peerTimeout(), content);
    }

    /** Test seam: explicit periods bypassing {@link MavlinkCoreSettings} — package-private, same idiom as this module's other services. */
    HeartbeatService(FrameSink sink, TxScheduler scheduler, PeerDirectory peers, Duration heartbeatPeriod,
                             Duration peerTimeout, HeartbeatContent content) {
        this.sink = Objects.requireNonNull(sink, "sink");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.peers = Objects.requireNonNull(peers, "peers");
        this.heartbeatPeriod = requirePositive(heartbeatPeriod, "heartbeatPeriod");
        this.peerTimeout = requirePositive(peerTimeout, "peerTimeout");
        this.content = Objects.requireNonNull(content, "content");
    }

    /** Starts the periodic emit. Idempotent — a second call while already started is a no-op. */
    public void start() {
        if (handle != null) {
            return;
        }
        handle = scheduler.repeat(TASK_NAME, heartbeatPeriod, this::tick);
    }

    /** Stops the periodic emit. Idempotent. */
    public void stop() {
        TxScheduler.Handle current = handle;
        if (current != null) {
            current.close();
            handle = null;
        }
    }

    /**
     * {@code true} if {@code id} was heard within {@code peerTimeout} as of the most recent tick;
     * {@code false} for a disconnected or never-known peer. Reflects only what has been observed as
     * of the last tick, not a live "now" recomputation — matches how {@link #start} drives this
     * service's own notion of time.
     */
    public boolean isConnected(PeerId id) {
        Objects.requireNonNull(id, "id");
        return Boolean.TRUE.equals(connectivity.get(id));
    }

    private void tick() {
        emitHeartbeat();
        updateConnectivity();
    }

    private void emitHeartbeat() {
        Heartbeat heartbeat = content.toMessage();
        Set<LinkId> links = new LinkedHashSet<>();
        for (Peer peer : peers.peers()) {
            links.add(peer.link());
        }
        for (LinkId link : links) {
            try {
                sink.broadcast(heartbeat, link);
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "Failed to broadcast our heartbeat on link " + link, e);
            }
        }
    }

    private void updateConnectivity() {
        Instant now = Instant.now();
        for (Peer peer : peers.peers()) {
            boolean connectedNow = Duration.between(peer.lastHeard(), now).compareTo(peerTimeout) <= 0;
            Boolean previous = connectivity.put(peer.id(), connectedNow);
            if (previous != null && previous != connectedNow) {
                if (connectedNow) {
                    LOG.log(Level.INFO, "Peer " + peer.id() + " is connected again");
                } else {
                    LOG.log(Level.WARNING, "Peer " + peer.id() + " disconnected -- no traffic within " + peerTimeout);
                }
            }
        }
    }

    private static Duration requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive, got " + value);
        }
        return value;
    }
}
