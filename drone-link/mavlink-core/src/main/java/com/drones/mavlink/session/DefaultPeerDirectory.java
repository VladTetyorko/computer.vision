package com.drones.mavlink.session;

import com.drones.mavlink.PeerId;
import com.drones.mavlink.codec.MavFrame;
import com.drones.mavlink.transport.LinkId;

import io.dronefleet.mavlink.minimal.Heartbeat;

import java.lang.System.Logger.Level;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The one {@link PeerDirectory} implementation. Thread-safe: {@link MavlinkSession} calls
 * {@link #recordFrame} from whichever link's own reader thread decoded a frame, so several threads
 * (one per registered link) may call it concurrently — backed by a {@link ConcurrentHashMap} keyed
 * by {@link PeerId}, updated atomically per peer via {@link Map#compute}.
 *
 * <p>{@link #recordFrame} is package-private on purpose: it is not part of the {@link PeerDirectory}
 * seam (read-only facts for L4 services), only {@link MavlinkSession} — the sole mutator — calls it.
 */
final class DefaultPeerDirectory implements PeerDirectory {

    private static final System.Logger LOG = System.getLogger(DefaultPeerDirectory.class.getName());

    /**
     * How recently the previous address must have been heard for a move to count as a <b>flap</b>
     * rather than an ordinary re-registration. A vehicle that went quiet and came back on a new
     * address is normal (a lease renewal, a reconnect); one that is transmitting from two addresses
     * at once is not.
     */
    private static final Duration FLAP_WINDOW = Duration.ofSeconds(3);

    /** Per-peer warning interval. A genuine roam must not be able to flood the log. */
    private static final Duration WARN_INTERVAL = Duration.ofSeconds(30);

    private final Map<PeerId, Peer> peers = new ConcurrentHashMap<>();
    private final Map<PeerId, Instant> lastFlapWarning = new ConcurrentHashMap<>();

    @Override
    public Collection<Peer> peers() {
        return List.copyOf(peers.values());
    }

    @Override
    public Peer peer(PeerId id) {
        Objects.requireNonNull(id, "id");
        return peers.get(id);
    }

    @Override
    public List<Peer> peersOnLink(LinkId link) {
        Objects.requireNonNull(link, "link");
        List<Peer> onLink = new ArrayList<>();
        for (Peer peer : peers.values()) {
            if (peer.link().equals(link)) {
                onLink.add(peer);
            }
        }
        return List.copyOf(onLink);
    }

    /**
     * Records {@code frame} as protocol evidence of its origin peer: refreshes {@code lastHeard}/
     * {@code link}/{@code address}, preserves {@code firstHeard} and the last known
     * {@link HeartbeatInfo} across updates, and replaces the heartbeat info when {@code frame}
     * itself is a {@code HEARTBEAT}. Called by {@link MavlinkSession} for every decoded frame,
     * before {@link Correlator} and {@link Dispatcher} see it.
     */
    void recordFrame(MavFrame frame) {
        PeerId id = new PeerId(frame.header().system(), frame.header().component());
        Peer[] previous = new Peer[1];
        peers.compute(id, (key, existing) -> {
            previous[0] = existing;
            Instant firstHeard = existing == null ? frame.receivedAt() : existing.firstHeard();
            HeartbeatInfo heartbeat = frame.is(Heartbeat.class)
                    ? heartbeatInfoOf(frame.as(Heartbeat.class))
                    : (existing == null ? null : existing.heartbeat());
            return new Peer(id, frame.link(), frame.source(), firstHeard, frame.receivedAt(), heartbeat);
        });
        // Logged outside compute(): the remapping function must stay side-effect free.
        warnOnAddressFlap(id, previous[0], frame);
    }

    /**
     * Makes a peer address that keeps moving visible, because everything downstream silently
     * follows it.
     *
     * <p>Every transmit to this peer goes to whatever address was recorded last, so two systems
     * sharing one sysid do not collide loudly — they take turns. A 33&nbsp;Hz RC override stream
     * split between them reaches each in bursts with gaps long enough to trip the vehicle's own
     * command-loss failsafe, and the only symptom is a vehicle that keeps disarming for no visible
     * reason. Overwriting the address is still the right behaviour (a lease renewal or a NAT
     * rebinding genuinely moves a peer, as {@link Peer} itself notes) — doing it in silence is not.
     */
    private void warnOnAddressFlap(PeerId id, Peer previous, MavFrame frame) {
        if (previous == null || previous.address().equals(frame.source())) {
            return;
        }
        if (Duration.between(previous.lastHeard(), frame.receivedAt()).compareTo(FLAP_WINDOW) > 0) {
            return;   // it had gone quiet; coming back elsewhere is a reconnect, not a conflict
        }
        Instant last = lastFlapWarning.get(id);
        if (last != null && Duration.between(last, frame.receivedAt()).compareTo(WARN_INTERVAL) < 0) {
            return;
        }
        lastFlapWarning.put(id, frame.receivedAt());
        LOG.log(Level.WARNING,
                "MAVLink sysid {0}/comp {1} is transmitting from two addresses at once: {2} then {3}."
                        + " Everything sent to this peer follows the most recent one, so a command"
                        + " stream is being split between them. Two vehicles sharing one sysid is the"
                        + " usual cause; a NAT or DHCP rebinding is the benign one.",
                id.system().value(), id.component().value(), previous.address(), frame.source());
    }

    private static HeartbeatInfo heartbeatInfoOf(Heartbeat heartbeat) {
        return new HeartbeatInfo(
                heartbeat.autopilot().value(),
                heartbeat.type().value(),
                heartbeat.baseMode().value(),
                heartbeat.customMode(),
                heartbeat.systemStatus().value());
    }
}
