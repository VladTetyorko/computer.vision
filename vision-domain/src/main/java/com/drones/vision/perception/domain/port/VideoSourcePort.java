package com.drones.vision.perception.domain.port;

import com.drones.vision.kernel.StreamDescriptor;
import com.drones.vision.kernel.StreamId;
import com.drones.vision.perception.domain.model.VideoFrame;

import java.util.concurrent.Flow;

/**
 * Driven port: obtain a live video feed for a device's stream.
 *
 * <p>One implementation exists per ingest protocol (RTSP, MJPEG, USB/UVC,
 * simulation, ...). {@code VisionApplication}'s {@code VideoSourceRegistry}
 * selects an implementation for a given {@link StreamDescriptor} via {@link
 * #supports(StreamDescriptor)}; adding a new protocol means adding a new
 * adapter behind this port, with no change to core code (open/closed).
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li>{@link #open(StreamId, StreamDescriptor)} returns a <b>per-open</b>
 *       publisher: each call creates an independent, freshly started feed
 *       for that {@code streamId}; publishers are not shared or cached
 *       across calls.</li>
 *   <li>The publisher is <b>hot and live</b> — it represents a real-time
 *       source, not a replayable sequence. A subscriber that is not
 *       currently requesting frames does not "catch up" later; frames
 *       produced while no demand exists are handled per the drop policy
 *       below, not buffered indefinitely.</li>
 *   <li>On an unrecoverable source failure (e.g. camera unreachable, decode
 *       error the adapter cannot continue past) the adapter must call
 *       {@link Flow.Subscriber#onError(Throwable)} on its subscriber(s) and
 *       stop producing frames. Recoverable hiccups (e.g. a transient
 *       network blip the adapter reconnects from) should not surface as
 *       {@code onError} — they are handled internally.</li>
 *   <li>{@link #close(StreamId)} stops the feed for the given stream and
 *       releases adapter-side resources (sockets, decoders, threads). It
 *       must be idempotent: closing an already-closed or unknown stream is
 *       a no-op, not an error.</li>
 * </ul>
 *
 * <h2>Backpressure</h2>
 * This port exposes {@link java.util.concurrent.Flow.Publisher} (JDK Flow;
 * no reactive framework dependency in the domain) specifically so
 * backpressure is part of the contract, not an afterthought. A slow
 * subscriber requests fewer frames via {@link Flow.Subscription#request(long)};
 * the <b>source adapter</b> — not the application layer — owns the drop
 * policy for frames produced faster than they are requested. For live
 * feeds that policy is <b>latest-wins</b>: when demand is exhausted, the
 * adapter drops the oldest buffered/pending frame(s) in favor of the most
 * recent one rather than blocking capture or unbounded buffering. This
 * keeps the video path always moving at the cost of occasionally skipping
 * frames, which is the right tradeoff for a live view.
 *
 * <h2>Threading</h2>
 * Adapters typically drive publication from their own I/O or capture
 * thread(s); {@link Flow.Subscriber} callbacks may therefore be invoked
 * from adapter-managed threads rather than the caller's thread. {@code
 * supports}, {@code open}, and {@code close} must be safe to call
 * concurrently for different {@code streamId}s (one stream pipeline per
 * JVM instance is the unit of horizontal scale, so independent streams
 * must never contend on shared adapter state).
 */
public interface VideoSourcePort {

    /**
     * Whether this adapter knows how to open the given descriptor (e.g. by
     * checking {@link StreamDescriptor#protocol()}).
     *
     * @param descriptor descriptor to check
     * @return {@code true} if this adapter can {@link #open(StreamId, StreamDescriptor)} it
     */
    boolean supports(StreamDescriptor descriptor);

    /**
     * Opens a live, hot publisher of frames for the given stream.
     *
     * @param id         identity to associate with the opened stream
     * @param descriptor how to obtain the feed
     * @return a per-open publisher of frames; see class javadoc for delivery/backpressure semantics
     */
    Flow.Publisher<VideoFrame> open(StreamId id, StreamDescriptor descriptor);

    /**
     * Stops the feed for the given stream and releases adapter resources.
     * Idempotent.
     *
     * @param id the stream to close
     */
    void close(StreamId id);
}
