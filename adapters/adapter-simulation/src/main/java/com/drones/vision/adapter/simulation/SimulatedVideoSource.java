package com.drones.vision.adapter.simulation;

import com.drones.vision.domain.model.PixelFormat;
import com.drones.vision.domain.model.StreamDescriptor;
import com.drones.vision.domain.model.StreamId;
import com.drones.vision.domain.model.VideoFrame;
import com.drones.vision.domain.port.out.VideoSourcePort;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.Flow;

/**
 * Synthetic {@link VideoSourcePort} implementation: generates video frames
 * in-process instead of reading from real hardware, so every phase of the
 * platform is testable and demo-able without a camera or drone attached.
 *
 * <p>Supports {@link StreamDescriptor#protocol()} {@code "sim"}. Recognized
 * {@link StreamDescriptor#options()} keys (all optional):
 * <ul>
 *   <li>{@code width} — frame width in pixels, default {@value #DEFAULT_WIDTH}</li>
 *   <li>{@code height} — frame height in pixels, default {@value #DEFAULT_HEIGHT}</li>
 *   <li>{@code fps} — target frames per second, default {@value #DEFAULT_FPS}</li>
 * </ul>
 *
 * <p>Each {@link #open(StreamId, StreamDescriptor)} call starts a dedicated,
 * single-threaded {@link ScheduledExecutorService} that renders a frame (a
 * moving filled circle, a frame counter, and the wall-clock time via
 * Java2D, JPEG-encoded via {@link javax.imageio.ImageIO}) on a fixed period
 * and submits it to a per-stream {@link SubmissionPublisher}. {@link
 * #close(StreamId)} stops that executor and closes the publisher; both are
 * idempotent, matching {@link VideoSourcePort}'s contract.
 *
 * <p>Plain class with no framework dependency — instantiated directly by
 * {@code vision-app}'s wiring configuration.
 */
public final class SimulatedVideoSource implements VideoSourcePort {

    private static final String PROTOCOL = "sim";
    static final int DEFAULT_WIDTH = 640;
    static final int DEFAULT_HEIGHT = 480;
    static final int DEFAULT_FPS = 15;

    private final Map<StreamId, StreamRuntime> runtimes = new ConcurrentHashMap<>();

    @Override
    public boolean supports(StreamDescriptor descriptor) {
        return descriptor != null && PROTOCOL.equals(descriptor.protocol());
    }

    @Override
    public Flow.Publisher<VideoFrame> open(StreamId id, StreamDescriptor descriptor) {
        if (!supports(descriptor)) {
            throw new IllegalArgumentException(
                    "SimulatedVideoSource does not support descriptor: " + descriptor);
        }
        int width = intOption(descriptor, "width", DEFAULT_WIDTH);
        int height = intOption(descriptor, "height", DEFAULT_HEIGHT);
        int fps = intOption(descriptor, "fps", DEFAULT_FPS);

        StreamRuntime runtime = new StreamRuntime(id, width, height, fps);
        StreamRuntime previous = runtimes.put(id, runtime);
        if (previous != null) {
            previous.close(); // defensive: an id must not have two live runtimes
        }
        runtime.start();
        return runtime.publisher;
    }

    @Override
    public void close(StreamId id) {
        StreamRuntime runtime = runtimes.remove(id);
        if (runtime != null) {
            runtime.close();
        }
    }

    private static int intOption(StreamDescriptor descriptor, String key, int defaultValue) {
        String raw = descriptor.options().get(key);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            int value = Integer.parseInt(raw.trim());
            return value > 0 ? value : defaultValue;
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /** Per-open runtime: a scheduled frame renderer feeding a {@link SubmissionPublisher}. */
    private static final class StreamRuntime {
        private final StreamId streamId;
        private final int width;
        private final int height;
        private final int fps;
        private final SubmissionPublisher<VideoFrame> publisher = new SubmissionPublisher<>();
        private final ScheduledExecutorService executor;
        private final AtomicLong sequence = new AtomicLong();
        private final AtomicBoolean closed = new AtomicBoolean(false);

        StreamRuntime(StreamId streamId, int width, int height, int fps) {
            this.streamId = streamId;
            this.width = width;
            this.height = height;
            this.fps = fps;
            this.executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "sim-video-" + streamId.value());
                thread.setDaemon(true);
                return thread;
            });
        }

        void start() {
            long periodMillis = Math.max(1L, 1000L / fps);
            executor.scheduleAtFixedRate(this::renderAndPublish, 0, periodMillis, TimeUnit.MILLISECONDS);
        }

        private void renderAndPublish() {
            if (closed.get()) {
                return;
            }
            try {
                long seq = sequence.getAndIncrement();
                byte[] jpeg = FrameRenderer.renderJpeg(width, height, seq);
                VideoFrame frame = new VideoFrame(streamId, seq, Instant.now(), width, height,
                        PixelFormat.JPEG, ByteBuffer.wrap(jpeg));
                publisher.submit(frame);
            } catch (IOException e) {
                publisher.closeExceptionally(e);
                close();
            } catch (RuntimeException e) {
                publisher.closeExceptionally(e);
                close();
            }
        }

        void close() {
            if (closed.compareAndSet(false, true)) {
                executor.shutdown();
                publisher.close();
            }
        }
    }
}
