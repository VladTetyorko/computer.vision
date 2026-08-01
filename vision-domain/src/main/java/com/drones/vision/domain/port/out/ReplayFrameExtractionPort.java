package com.drones.vision.domain.port.out;

import com.drones.vision.domain.model.StreamId;
import com.drones.vision.domain.model.VideoFrame;

import java.time.Instant;
import java.util.Optional;

/**
 * Driven port: pull one decoded frame out of a stream's durable recording, at a specific instant
 * (docs/CV-TRAINING-V2-PLAN.md §3) — the "capture a training frame from replay" counterpart to
 * live capture's {@code StreamService#latestRawFrame}.
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li>{@link #frameAt(StreamId, Instant)} returns {@link Optional#empty()} for honest absence —
 *       no recording configured for the stream, recording disabled on the media server, or nothing
 *       recorded at that instant — never an error. This mirrors {@code
 *       StreamPublisherPort#playbackUrl}'s own posture toward a missing recording.</li>
 *   <li>Implementations must stamp the returned {@link VideoFrame}'s {@code capturedAt} with the
 *       requested {@code at} and its {@code sequence} with {@code 0}, so callers never have to
 *       reconcile two notions of "when" — the frame's own timestamp always equals what was asked
 *       for.</li>
 * </ul>
 *
 * <h2>Threading</h2>
 * Implementations must be safe for concurrent use — this is a request-thread, on-demand fetch, not
 * a hot/live subscription like {@code VideoSourcePort#open}, and calls for the same or different
 * streams may run concurrently.
 */
public interface ReplayFrameExtractionPort {

    /**
     * Pulls one decoded frame out of {@code streamId}'s recording at instant {@code at}.
     *
     * @param streamId the recorded stream to read from
     * @param at       the instant to extract a frame for
     * @return the frame at {@code at}, or {@link Optional#empty()} if none is available — see the
     * type-level Contract for what "none available" covers
     */
    Optional<VideoFrame> frameAt(StreamId streamId, Instant at);
}
