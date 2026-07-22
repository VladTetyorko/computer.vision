package com.drones.vision.domain.port.out;

import com.drones.vision.domain.model.Device;
import com.drones.vision.domain.model.StreamId;
import com.drones.vision.domain.model.VideoFrame;

import java.net.URI;
import java.util.Optional;

/**
 * Driven port: fan out video frames to viewers (e.g. HLS/WebRTC egress).
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li>{@link #streamStarted(StreamId, Device)} is called once, before any
 *       {@link #publish(StreamId, VideoFrame)} call for that stream, so the
 *       adapter can set up whatever session/output it needs (e.g. an HLS
 *       segment writer).</li>
 *   <li>{@link #publish(StreamId, VideoFrame)} is called for every frame on
 *       the video path (not just sampled/inference frames) — implementations
 *       must be cheap per call and must not block on slow downstream
 *       consumers; if a downstream sink is slow, the adapter — not the
 *       core pipeline — is responsible for its own drop/queue policy,
 *       mirroring the latest-wins approach used for source ingestion.</li>
 *   <li>{@link #streamEnded(StreamId)} is called once when the stream stops
 *       (normally or due to error) so the adapter can release resources.
 *       Implementations should tolerate {@code streamEnded} for a stream
 *       that was never started or already ended (idempotent, no-op).</li>
 * </ul>
 *
 * <h2>Threading</h2>
 * These methods are invoked from the owning stream's pipeline thread(s) in
 * the fixed order {@code streamStarted → publish* → streamEnded} for a
 * given {@code streamId}. Calls for different streams may happen
 * concurrently on different threads (one pipeline per stream, per the
 * platform's horizontal-scale model); implementations must not share
 * mutable state across streams without synchronization.
 */
public interface StreamPublisherPort {

    /**
     * Signals that publishing is starting for a stream.
     *
     * @param id     the stream starting
     * @param device the device the stream belongs to
     */
    void streamStarted(StreamId id, Device device);

    /**
     * Publishes one frame for the given stream.
     *
     * @param id    the stream the frame belongs to
     * @param frame the frame to publish
     */
    void publish(StreamId id, VideoFrame frame);

    /**
     * Signals that a stream has ended; implementations release any
     * resources allocated in {@link #streamStarted(StreamId, Device)}.
     * Idempotent.
     *
     * @param id the stream that ended
     */
    void streamEnded(StreamId id);

    /**
     * Where a viewer can watch the published stream (e.g. an HLS playlist
     * URL), if this publisher exposes one.
     *
     * <p>Default implementations that have no viewing endpoint (e.g. a
     * no-op/dev-support publisher) simply return {@link Optional#empty()};
     * publishers backed by a media server override this once a stream has
     * been started.
     *
     * @param id the stream to look up
     * @return the viewer URL, or {@link Optional#empty()} if this publisher has no viewing endpoint
     */
    default Optional<URI> viewUrl(StreamId id) {
        return Optional.empty();
    }
}
