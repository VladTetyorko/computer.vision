package com.drones.vision.perception.domain.port;

import com.drones.vision.perception.domain.model.CameraAttitude;
import com.drones.vision.perception.domain.model.DetectionResult;
import com.drones.vision.perception.domain.model.PipelineConfig;
import com.drones.vision.kernel.StreamId;

import java.net.URI;
import java.util.concurrent.Flow;

/**
 * Driven port: subscribe to a worker that pulls frames from a stream's own source (typically an
 * RTSP path served by mediamtx, docs/plans/active/MEDIA-SOT-PLAN.md &sect;5.2) and decodes/infers
 * on them itself, instead of the JVM pushing sampled frames to it.
 *
 * <h2>Why this is a sibling of {@link DetectionPort}, not an overload</h2>
 * {@link DetectionPort#detect} is request&rarr;response: the caller holds a decoded {@code
 * VideoFrame} and asks for one inference. A pull has no request to make — the worker decodes and
 * infers on its own schedule, and results arrive unsolicited on whatever cadence it chooses. There
 * is no frame to pass in, no {@code CompletionStage} to correlate a reply against, and no place a
 * fourth/fifth argument on {@code detect} could express "stop sending me a frame and start
 * subscribing instead". Two shapes, two interfaces.
 *
 * <h2>Mirrors {@code VideoSourcePort} on purpose</h2>
 * {@code open}/{@code close} here are the same shape as {@link VideoSourcePort#open}/{@link
 * VideoSourcePort#close}: a per-{@code streamId}, hot/live {@link Flow.Publisher}, latest-wins
 * under backpressure, unrecoverable failure surfaced via {@code onError}. That is deliberate — it
 * is what lets the same generic {@code SupervisedPublisher<T>} (already used to give a {@code
 * VideoSourcePort} reopen-with-backoff resilience) wrap this port's output as {@code
 * SupervisedPublisher<DetectionResult>} with no new supervision code.
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li>{@link #open(StreamId, URI, PipelineConfig)} starts a pull for {@code sourceUrl} (the
 *       mediamtx RTSP path for {@code id}) and returns a <b>per-open</b> publisher of {@link
 *       DetectionResult}s; each call is an independent, freshly started pull, never shared or
 *       cached across calls.</li>
 *   <li>The publisher is <b>hot and live</b>, exactly like {@code VideoSourcePort}'s: a subscriber
 *       that is not currently requesting items does not "catch up" on results produced while it
 *       had no demand.</li>
 *   <li>On an unrecoverable failure (source unopenable, or stalled past the worker's own timeout)
 *       the implementation must call {@link Flow.Subscriber#onError(Throwable)} and stop the pull.
 *       Recoverable hiccups are handled internally and must not surface as {@code onError}.</li>
 *   <li>{@link #reconfigure(StreamId, PipelineConfig)} restates the <em>hot</em> fields of an
 *       already-open pull — model, confidence threshold, target fps, tracking — the same fields a
 *       push-mode call re-sends on every {@code FrameRequest}. Calling it for a stream with no open
 *       pull is a no-op.</li>
 *   <li>{@link #attitude(StreamId, CameraAttitude)} forwards the camera's current pose to the
 *       worker at whatever cadence telemetry arrives, decoupled from the frame rate — the pull
 *       equivalent of {@link DetectionPort}'s 3-argument {@code detect} overload. Calling it for a
 *       stream with no open pull is a no-op.</li>
 *   <li>{@link #close(StreamId)} ends the pull and releases adapter-side resources. Idempotent:
 *       closing an already-closed or never-opened stream is a no-op, not an error.</li>
 * </ul>
 *
 * <h2>Backpressure</h2>
 * Same policy as {@link VideoSourcePort}: a slow subscriber requests fewer items via {@link
 * Flow.Subscription#request(long)}, and the adapter — not the application layer — owns the drop
 * policy for results produced faster than they are requested. There is no in-flight bound to honor
 * here the way {@link DetectionPort} callers must honor {@link
 * PipelineConfig#maxInFlightInferences()}: the worker, not this JVM, paces its own inference loop.
 *
 * <h2>Threading</h2>
 * Implementations typically drive delivery from their own I/O thread(s) (e.g. a gRPC stream
 * receive loop); {@link Flow.Subscriber} callbacks may be invoked from adapter-managed threads.
 * {@code open}, {@code reconfigure}, {@code attitude}, and {@code close} must be safe to call
 * concurrently for different {@code streamId}s.
 */
public interface PulledDetectionPort {

    /**
     * Starts a pull against {@code sourceUrl} and returns a live publisher of its results.
     *
     * @param id        identity to associate with the opened pull
     * @param sourceUrl where the worker should pull frames from (e.g. an {@code rtsp://} mediamtx
     *                  path URI)
     * @param config    model, threshold, sampling and tracking configuration
     * @return a per-open publisher of detection results; see class javadoc for delivery/backpressure semantics
     */
    Flow.Publisher<DetectionResult> open(StreamId id, URI sourceUrl, PipelineConfig config);

    /**
     * Restates the hot fields of an already-open pull (model, confidence threshold, target fps,
     * tracking). A no-op if {@code id} has no open pull.
     *
     * @param id     the pull to reconfigure
     * @param config the new configuration; only the hot fields take effect immediately
     */
    void reconfigure(StreamId id, PipelineConfig config);

    /**
     * Forwards the camera's current pose, to be applied to whatever frame the worker analyses
     * next. A no-op if {@code id} has no open pull.
     *
     * @param id       the pull to update
     * @param attitude where the camera was pointing; never {@code null}
     */
    void attitude(StreamId id, CameraAttitude attitude);

    /**
     * Stops the pull for the given stream and releases adapter resources. Idempotent.
     *
     * @param id the stream to close
     */
    void close(StreamId id);
}
