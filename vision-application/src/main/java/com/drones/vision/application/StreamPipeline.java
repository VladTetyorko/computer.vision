package com.drones.vision.application;

import com.drones.vision.domain.model.Detection;
import com.drones.vision.domain.model.DetectionResult;
import com.drones.vision.domain.model.Device;
import com.drones.vision.domain.model.Event;
import com.drones.vision.domain.model.EventType;
import com.drones.vision.domain.model.PipelineConfig;
import com.drones.vision.domain.model.StreamId;
import com.drones.vision.domain.model.VideoFrame;
import com.drones.vision.domain.port.out.DetectionPort;
import com.drones.vision.domain.port.out.DetectionRepositoryPort;
import com.drones.vision.domain.port.out.EventPublisherPort;
import com.drones.vision.domain.port.out.StreamPublisherPort;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

/**
 * Per-stream pipeline runtime: subscribes to a video source, publishes every
 * frame, samples a subset of frames for CV inference, and fans out
 * detections/events.
 *
 * <p>One instance exists per running stream (created by {@link
 * StreamService}), consistent with the platform's scalability decision that
 * a stream pipeline is pinned to a single JVM instance and holds no
 * static/global mutable state.
 *
 * <h2>Frame path</h2>
 * <ul>
 *   <li>Subscribes to the source {@link Flow.Publisher} as a {@link
 *       Flow.Subscriber}, requesting exactly one frame at a time ({@code
 *       request(1)}). This pipeline never buffers demand ahead of what it is
 *       currently processing; the source adapter owns the drop policy for a
 *       live feed when this subscriber isn't ready (latest-wins), per {@code
 *       VideoSourcePort}'s contract.</li>
 *   <li>Every frame is forwarded to {@link StreamPublisherPort#publish}
 *       regardless of whether it is sampled for inference.</li>
 *   <li><b>Phase 2 seam:</b> overlay rendering is intentionally not wired in
 *       yet. Once {@code OverlayPort} lands, this is where a frame would be
 *       combined with {@link #latestDetections()} into an {@code
 *       AnnotatedFrame} and rendered before publishing, so inference FPS can
 *       keep scaling independently of video FPS.</li>
 * </ul>
 *
 * <h2>Inference sampling</h2>
 * Every Nth frame is sampled for inference, where N is derived from {@link
 * PipelineConfig#inferenceFps()} and the source's <b>measured</b> arrival
 * rate: each frame's inter-arrival delta (from an injectable {@link
 * LongSupplier} nanotime source, defaulting to {@link System#nanoTime()}) is
 * folded into an exponentially-weighted moving average (EWMA, {@value
 * #MEASURED_FPS_EWMA_ALPHA}) of the source frame rate. Until {@value
 * #WARMUP_FRAMES} frames have arrived, the measurement is not yet trusted and
 * a {@value #ASSUMED_SOURCE_FPS} fps assumption is used instead; the measured
 * rate is sanity-clamped to {@code [}{@value #MIN_MEASURED_FPS}{@code ,}
 * {@value #MAX_MEASURED_FPS}{@code ]} fps to guard against a stalled/degenerate
 * clock. The sampling interval ({@code everyNth = max(1, round(effectiveFps /
 * inferenceFps))}) is recomputed on every frame as the measurement updates,
 * so it tracks a source whose actual rate differs from — or drifts away from
 * — the 30fps assumption. A sampled frame is only submitted to {@link
 * DetectionPort#detect} if fewer than {@link
 * PipelineConfig#maxInFlightInferences()} calls are currently in flight;
 * otherwise it is skipped — never queued — so a slow CV service can never
 * stall the video path.
 *
 * <p>The most recently completed detection result is kept in a {@code
 * volatile} field ({@link #latestDetections()}); it is persisted via {@link
 * DetectionRepositoryPort} and announced via a {@code DETECTION} {@link
 * Event} only when non-empty, so uneventful frames don't spam storage/events.
 *
 * <h2>Error handling &amp; lifecycle</h2>
 * Any failure on the source ({@link #onError}) or from a detection call
 * publishes a single {@code PIPELINE_ERROR} event and stops the pipeline
 * cleanly (cancels the subscription, signals {@link
 * StreamPublisherPort#streamEnded}). {@link #close()} is idempotent and safe
 * to call from any thread, including concurrently with an in-flight {@code
 * onNext}.
 */
public final class StreamPipeline implements Flow.Subscriber<VideoFrame>, AutoCloseable {

    /** Assumed source frame rate used before the measured rate is trusted (see {@link #WARMUP_FRAMES}). */
    static final int ASSUMED_SOURCE_FPS = 30;

    /** Smoothing factor for the source frame-rate EWMA; lower = smoother/slower to react. */
    static final double MEASURED_FPS_EWMA_ALPHA = 0.2;

    /** Number of frame arrivals observed before the measured rate replaces {@link #ASSUMED_SOURCE_FPS}. */
    static final int WARMUP_FRAMES = 5;

    /** Sanity clamp bounds for the measured source frame rate. */
    static final double MIN_MEASURED_FPS = 1.0;
    static final double MAX_MEASURED_FPS = 240.0;

    private final StreamId streamId;
    private final Device device;
    private final PipelineConfig config;
    private final Flow.Publisher<VideoFrame> source;
    private final DetectionPort detectionPort;
    private final StreamPublisherPort streamPublisherPort;
    private final DetectionRepositoryPort detectionRepositoryPort;
    private final EventPublisherPort eventPublisher;
    private final LongSupplier nanoTimeSource;

    private final AtomicInteger inFlightInferences = new AtomicInteger();
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private volatile Flow.Subscription subscription;
    private volatile List<Detection> latestDetections = List.of();

    // Frame-arrival cadence measurement state. Only ever touched from within
    // onNext(), which Flow.Subscriber's contract serializes (signals are
    // never delivered concurrently) -- volatile purely for cross-thread
    // *visibility* between successive onNext calls, not for mutual exclusion.
    private volatile long lastFrameArrivalNanos = -1L;
    private volatile long framesObserved = 0L;
    private volatile double measuredFps = -1.0;
    private volatile long sampleEveryNthFrame;

    public StreamPipeline(StreamId streamId, Device device, PipelineConfig config,
                           Flow.Publisher<VideoFrame> source, DetectionPort detectionPort,
                           StreamPublisherPort streamPublisherPort, DetectionRepositoryPort detectionRepositoryPort,
                           EventPublisherPort eventPublisher) {
        this(streamId, device, config, source, detectionPort, streamPublisherPort, detectionRepositoryPort,
                eventPublisher, System::nanoTime);
    }

    /**
     * Test seam: same as the public constructor but with an injectable
     * nanotime source for the frame-cadence measurement, so tests can drive
     * deterministic synthetic frame rates instead of depending on real
     * wall-clock timing.
     */
    StreamPipeline(StreamId streamId, Device device, PipelineConfig config,
                   Flow.Publisher<VideoFrame> source, DetectionPort detectionPort,
                   StreamPublisherPort streamPublisherPort, DetectionRepositoryPort detectionRepositoryPort,
                   EventPublisherPort eventPublisher, LongSupplier nanoTimeSource) {
        this.streamId = Objects.requireNonNull(streamId, "streamId must not be null");
        this.device = Objects.requireNonNull(device, "device must not be null");
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.source = Objects.requireNonNull(source, "source must not be null");
        this.detectionPort = Objects.requireNonNull(detectionPort, "detectionPort must not be null");
        this.streamPublisherPort = Objects.requireNonNull(streamPublisherPort, "streamPublisherPort must not be null");
        this.detectionRepositoryPort =
                Objects.requireNonNull(detectionRepositoryPort, "detectionRepositoryPort must not be null");
        this.eventPublisher = Objects.requireNonNull(eventPublisher, "eventPublisher must not be null");
        this.nanoTimeSource = Objects.requireNonNull(nanoTimeSource, "nanoTimeSource must not be null");
        this.sampleEveryNthFrame = everyNth(ASSUMED_SOURCE_FPS);
    }

    /**
     * Signals {@link StreamPublisherPort#streamStarted} and subscribes to the
     * source publisher, beginning frame processing. Must be called exactly
     * once.
     */
    public void start() {
        streamPublisherPort.streamStarted(streamId, device);
        source.subscribe(this);
    }

    /**
     * @return the most recently completed detection result's detections, or
     *         an empty list if no inference has completed yet. Updated for
     *         every completed inference, including empty results, so callers
     *         (e.g. a future overlay) never render stale boxes past the point
     *         a tracked object disappeared.
     */
    public List<Detection> latestDetections() {
        return latestDetections;
    }

    @Override
    public void onSubscribe(Flow.Subscription subscription) {
        this.subscription = subscription;
        subscription.request(1);
    }

    @Override
    public void onNext(VideoFrame frame) {
        if (closed.get()) {
            return;
        }
        recordArrivalAndRecomputeSampling();
        try {
            streamPublisherPort.publish(streamId, frame);
            if (frame.sequence() % sampleEveryNthFrame == 0) {
                maybeDetect(frame);
            }
        } catch (RuntimeException e) {
            handleError(e);
            return;
        }
        if (!closed.get()) {
            subscription.request(1);
        }
    }

    /**
     * Folds this frame's arrival into the source frame-rate measurement and
     * recomputes {@link #sampleEveryNthFrame} from the result. Cannot throw
     * (deliberately kept outside the publish/detect try-catch below, which
     * only handles collaborator failures).
     */
    private void recordArrivalAndRecomputeSampling() {
        long now = nanoTimeSource.getAsLong();
        framesObserved++;
        if (lastFrameArrivalNanos >= 0) {
            long deltaNanos = now - lastFrameArrivalNanos;
            if (deltaNanos > 0) {
                double instantaneousFps = 1_000_000_000.0 / deltaNanos;
                double blended = measuredFps < 0
                        ? instantaneousFps // seed directly from the first sample rather than blending toward an
                                            // arbitrary starting value, so a constant-cadence source converges
                                            // immediately instead of drifting in slowly over many EWMA updates
                        : MEASURED_FPS_EWMA_ALPHA * instantaneousFps + (1 - MEASURED_FPS_EWMA_ALPHA) * measuredFps;
                measuredFps = clamp(blended, MIN_MEASURED_FPS, MAX_MEASURED_FPS);
            }
        }
        lastFrameArrivalNanos = now;

        boolean trustMeasurement = framesObserved >= WARMUP_FRAMES && measuredFps >= 0;
        sampleEveryNthFrame = everyNth(trustMeasurement ? measuredFps : ASSUMED_SOURCE_FPS);
    }

    private long everyNth(double effectiveFps) {
        return Math.max(1L, Math.round(effectiveFps / config.inferenceFps()));
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private void maybeDetect(VideoFrame frame) {
        if (inFlightInferences.get() >= config.maxInFlightInferences()) {
            return; // bounded in-flight: skip this sample rather than queue it
        }
        inFlightInferences.incrementAndGet();
        detectionPort.detect(frame, config).whenComplete((result, error) -> {
            inFlightInferences.decrementAndGet();
            if (closed.get()) {
                return;
            }
            if (error != null) {
                handleError(unwrap(error));
                return;
            }
            onDetectionResult(result);
        });
    }

    private void onDetectionResult(DetectionResult result) {
        latestDetections = result.detections();
        if (!result.detections().isEmpty()) {
            detectionRepositoryPort.save(result);
            eventPublisher.publish(Event.of(streamId, EventType.DETECTION,
                    "Detected " + result.detections().size() + " object(s) on frame " + result.frameSequence()));
        }
    }

    @Override
    public void onError(Throwable throwable) {
        handleError(throwable);
    }

    @Override
    public void onComplete() {
        close();
    }

    private void handleError(Throwable throwable) {
        if (closed.compareAndSet(false, true)) {
            eventPublisher.publish(Event.of(streamId, EventType.PIPELINE_ERROR,
                    throwable == null ? "unknown error" : String.valueOf(throwable.getMessage())));
            doClose();
        }
    }

    /**
     * Stops the pipeline: cancels the source subscription and signals {@link
     * StreamPublisherPort#streamEnded}. Idempotent — safe to call multiple
     * times and from any thread, including concurrently with an in-flight
     * {@code onNext} or detection completion.
     */
    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            doClose();
        }
    }

    private void doClose() {
        Flow.Subscription s = subscription;
        if (s != null) {
            s.cancel();
        }
        streamPublisherPort.streamEnded(streamId);
    }

    private static Throwable unwrap(Throwable t) {
        return (t instanceof CompletionException && t.getCause() != null) ? t.getCause() : t;
    }
}
