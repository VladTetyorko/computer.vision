package com.drones.vision.domain.port.out;

import com.drones.vision.domain.model.Device;
import com.drones.vision.domain.model.StreamId;
import com.drones.vision.domain.model.VideoFrame;

/**
 * Driven port: persist video frames to durable storage (e.g. segmented MP4
 * recording with a retention policy).
 *
 * <p>Method shape mirrors {@link StreamPublisherPort} exactly ({@code
 * streamStarted}/{@code publish}/{@code streamEnded}), but this is a
 * distinct port: publishing (live egress to viewers) and recording
 * (durable storage) are different concerns with different adapters
 * (e.g. {@code adapter-publish-hls} vs {@code adapter-recording}) that the
 * pipeline invokes independently and that may be enabled/disabled
 * separately per stream.
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li>{@link #streamStarted(StreamId, Device)} is called once, before any
 *       {@link #publish(StreamId, VideoFrame)} call for that stream, so the
 *       adapter can open whatever recording session/segment writer it
 *       needs.</li>
 *   <li>{@link #publish(StreamId, VideoFrame)} is called for every frame on
 *       the video path; implementations must be cheap per call and must not
 *       block the pipeline on slow storage I/O — buffering/backpressure to
 *       disk is the adapter's responsibility.</li>
 *   <li>{@link #streamEnded(StreamId)} is called once when the stream stops
 *       so the adapter can finalize and close the recording. Must be
 *       idempotent for a stream that was never started or already ended.</li>
 * </ul>
 *
 * <h2>Threading</h2>
 * Same as {@link StreamPublisherPort}: methods for a given {@code
 * streamId} are invoked in order {@code streamStarted → publish* →
 * streamEnded} from that stream's pipeline thread(s); calls for different
 * streams may happen concurrently and must not share mutable state without
 * synchronization.
 */
public interface RecordingPort {

    /**
     * Signals that recording is starting for a stream.
     *
     * @param id     the stream starting
     * @param device the device the stream belongs to
     */
    void streamStarted(StreamId id, Device device);

    /**
     * Records one frame for the given stream.
     *
     * @param id    the stream the frame belongs to
     * @param frame the frame to record
     */
    void publish(StreamId id, VideoFrame frame);

    /**
     * Signals that a stream has ended; implementations finalize/close any
     * recording started in {@link #streamStarted(StreamId, Device)}.
     * Idempotent.
     *
     * @param id the stream that ended
     */
    void streamEnded(StreamId id);
}
