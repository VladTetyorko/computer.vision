package com.drones.mavlink.session;

import com.drones.mavlink.PeerId;
import com.drones.mavlink.codec.MavFrame;
import com.drones.mavlink.transport.LinkId;

import io.dronefleet.mavlink.minimal.Heartbeat;

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

    private final Map<PeerId, Peer> peers = new ConcurrentHashMap<>();

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
        peers.compute(id, (key, existing) -> {
            Instant firstHeard = existing == null ? frame.receivedAt() : existing.firstHeard();
            HeartbeatInfo heartbeat = frame.is(Heartbeat.class)
                    ? heartbeatInfoOf(frame.as(Heartbeat.class))
                    : (existing == null ? null : existing.heartbeat());
            return new Peer(id, frame.link(), frame.source(), firstHeard, frame.receivedAt(), heartbeat);
        });
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
