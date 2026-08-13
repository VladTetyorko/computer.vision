package com.drones.vision.perception.domain.port;

import com.drones.vision.perception.domain.model.FeedId;
import com.drones.vision.perception.domain.model.FeedSpec;
import com.drones.vision.kernel.StreamDescriptor;

/**
 * Driven port: transmit a local media source over a real wire protocol so
 * the platform's own {@link VideoSourcePort} adapters can ingest it exactly
 * like real hardware.
 *
 * <p>This is the TX (transmit) half of the RX/TX doctrine described in
 * {@code docs/main/CYCLES-PLAN.md} §0: every protocol adapter that receives from
 * real hardware today can, when it also has a {@code FeedTransmitterPort}
 * implementation, push a user-supplied file (or synthetic feed) out over
 * that same protocol to a real endpoint. This is deliberately <b>simulation
 * infrastructure, not egress</b> — it is unrelated to {@link
 * StreamPublisherPort}, which fans frames out to viewers. A {@code
 * FeedTransmitterPort} implementation exists so zero-hardware demos, E2E
 * tests of the full protocol path (encoder → wire → demuxer → pipeline),
 * and operator rehearsal ("replay yesterday's flight as if it were live")
 * can all exercise the exact same receive path real hardware would.
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li>{@link #supports(FeedSpec)} reports whether this adapter knows how
 *       to transmit the given spec (e.g. by checking {@link
 *       FeedSpec#protocol()} and the {@link FeedSpec#source()} URI scheme).</li>
 *   <li>{@link #start(FeedId, FeedSpec)} performs blocking setup (e.g.
 *       validating the source is readable, opening the target) and then
 *       transmits in the background — it does not block until the first
 *       frame has actually reached the wire. The returned {@link
 *       StreamDescriptor} is the descriptor the RX side can immediately
 *       attempt to open; depending on the protocol, the target may only
 *       become readable once data starts flowing (e.g. an RTSP path
 *       published to mediamtx exists as a readable path only after the
 *       first frame arrives) — callers that need frames to actually be
 *       flowing before opening the RX side must poll or retry rather than
 *       assume readiness the instant {@code start} returns.</li>
 *   <li>An unrecoverable transmit failure (e.g. the target endpoint
 *       disappears mid-transmission) simply stops the feed — there is no
 *       error channel back to the caller. This is a deliberate KISS
 *       tradeoff for simulation infrastructure (not a production egress
 *       path): a caller that needs to know a feed died should poll the RX
 *       side it is feeding instead.</li>
 *   <li>{@link #stop(FeedId)} stops transmission for the given feed and
 *       releases adapter resources. It must be idempotent: stopping an
 *       already-stopped or unknown feed is a no-op, not an error.</li>
 * </ul>
 *
 * <h2>Threading</h2>
 * {@link #start(FeedId, FeedSpec)} does its setup on the caller's thread but
 * hands transmission off to an adapter-managed background thread before
 * returning. {@code supports}, {@code start}, and {@code stop} must be safe
 * to call concurrently for different {@code id}s (one feed per simulated
 * source is the unit of concurrency here); a stale feed under a reused
 * {@code id} must be defensively stopped before a new one starts.
 */
public interface FeedTransmitterPort {

    /**
     * Whether this adapter knows how to {@link #start(FeedId, FeedSpec)} the
     * given spec (e.g. by checking {@link FeedSpec#protocol()} and the
     * {@link FeedSpec#source()} URI scheme).
     *
     * @param spec spec to check
     * @return {@code true} if this adapter can transmit it
     */
    boolean supports(FeedSpec spec);

    /**
     * Starts transmitting {@code spec}'s source over this adapter's
     * protocol, identified by {@code id}. Setup (e.g. validating the source,
     * opening the target) is blocking; the actual transmission runs in the
     * background, so this method returns once setup succeeds rather than
     * once the first frame has reached the wire.
     *
     * @param id   identity to associate with the started feed
     * @param spec what to transmit and how
     * @return the descriptor the RX side can use to ingest this feed
     */
    StreamDescriptor start(FeedId id, FeedSpec spec);

    /**
     * Stops the feed for the given id and releases adapter resources.
     * Idempotent.
     *
     * @param id the feed to stop
     */
    void stop(FeedId id);
}
