package com.drones.mavlink.session;

import com.drones.mavlink.PeerId;
import com.drones.mavlink.codec.MavFrame;
import com.drones.mavlink.transport.LinkId;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The one {@link LinkHealth} implementation.
 *
 * <h2>Drop-rate accounting — ours, not standard</h2>
 * The MAVLink spec pins no formula for {@code seq}-based loss (plan §2.1); this class's rule: for
 * two consecutive frames from the same {@code (PeerId, LinkId)} with sequence numbers {@code prev}
 * and {@code next}, the wrapped gap is {@code (next - prev) mod 256}. A gap of {@code 1} is the
 * expected back-to-back case (0 additional loss); a gap of {@code g > 1} means {@code g - 1} frames
 * are presumed lost between them. A gap of {@code 0} (a repeated sequence number — either a genuine
 * duplicate or, vanishingly unlikely, an exact 256-frame-later coincidence) is counted as received
 * but contributes no loss, since the two cases are indistinguishable from {@code seq} alone.
 * {@code dropRate = lost / (received + lost)} — the fraction of the <i>expected</i> stream that
 * never arrived, {@code 0.0} before anything has been received.
 *
 * <h2>Accounting is per {@code (PeerId, LinkId)}</h2>
 * If a peer is next heard on a <b>different</b> link than the one its running counters belong to,
 * this class starts a fresh window rather than diffing sequence numbers across two physically
 * distinct transmitters' counters (plan: "expected-vs-received seq accounting per (PeerId, LinkId),
 * handling the 8-bit wrap"). Since {@link Peer} keeps only the single most-recently-heard link per
 * peer (latest wins, matching {@link PeerDirectory}'s own protocol-facts-only, no-history
 * philosophy), {@link #of} answers for whichever link this peer was most recently heard on.
 *
 * <h2>Threading</h2>
 * Backed by a {@link ConcurrentHashMap}; {@link #recordFrame} is called from whichever link's
 * reader thread decoded the frame, {@link #of} from any caller thread. Each per-peer window is an
 * immutable snapshot replaced atomically via {@link Map#compute}, so {@link #of} never observes a
 * torn update.
 */
public final class DefaultLinkHealth implements LinkHealth {

    private final Duration peerTimeout;
    private final Map<PeerId, Window> windows = new ConcurrentHashMap<>();

    public DefaultLinkHealth(Duration peerTimeout) {
        this.peerTimeout = Objects.requireNonNull(peerTimeout, "peerTimeout");
    }

    @Override
    public Health of(PeerId id) {
        Objects.requireNonNull(id, "id");
        Window window = windows.get(id);
        if (window == null) {
            return new Health(id, false, null, 0, 0, 0.0);
        }
        boolean connected = Duration.between(window.lastHeard, Instant.now()).compareTo(peerTimeout) <= 0;
        double dropRate = (window.received + window.lost) == 0 ? 0.0
                : (double) window.lost / (window.received + window.lost);
        return new Health(id, connected, window.lastHeard, window.received, window.lost, dropRate);
    }

    /**
     * Called by {@link MavlinkSession} for every decoded frame, before {@link Correlator}/
     * {@link Dispatcher} see it.
     */
    void recordFrame(MavFrame frame) {
        PeerId id = new PeerId(frame.header().system(), frame.header().component());
        LinkId link = frame.link();
        int seq = frame.header().sequence();
        Instant receivedAt = frame.receivedAt();
        windows.compute(id, (key, existing) -> {
            if (existing == null || !existing.link.equals(link)) {
                return new Window(link, seq, receivedAt, 1, 0);
            }
            int gap = Math.floorMod(seq - existing.lastSeq, 256);
            long additionalLoss = gap == 0 ? 0 : gap - 1;
            return new Window(link, seq, receivedAt, existing.received + 1, existing.lost + additionalLoss);
        });
    }

    private static final class Window {
        final LinkId link;
        final int lastSeq;
        final Instant lastHeard;
        final long received;
        final long lost;

        Window(LinkId link, int lastSeq, Instant lastHeard, long received, long lost) {
            this.link = link;
            this.lastSeq = lastSeq;
            this.lastHeard = lastHeard;
            this.received = received;
            this.lost = lost;
        }
    }
}
