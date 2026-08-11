package com.drones.vision.adapter.rtsp;

import com.drones.vision.domain.model.PixelFormat;
import com.drones.vision.domain.model.StreamId;
import com.drones.vision.domain.model.VideoFrame;

import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;

import java.net.URI;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.Flow;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-{@code open} runtime for {@link FfmpegVideoSource}: one dedicated grab thread feeding a
 * {@link SubmissionPublisher}. Promoted out of {@code FfmpegVideoSource} (formerly the nested
 * {@code StreamRuntime} class, docs/plans/active/LAYERING-REFACTOR-PLAN.md §5.1) into its own top-level,
 * package-private class with <b>zero references back to the {@link FfmpegVideoSource} instance</b>
 * that created it — every dependency it needs ({@link FfmpegSettings}, the shared consecutive-open-
 * failure counter map) is passed in explicitly through its constructor, exactly as before (the
 * pre-split nested class was already {@code static}, so this promotion is a physical/file move, not
 * a behavior change).
 *
 * <p>Real-time pacing (paced {@code file}-scheme sources only), {@code loop} handling, the {@code
 * grabImage()} (not {@code grab()}) choice, and the WARN-on-open-failure logging are all unchanged
 * from the pre-split behavior — see {@code FfmpegVideoSource}'s class javadoc and this module's
 * MODULE.md for the full rationale of each.
 */
final class FfmpegGrabLoop {

    private static final System.Logger LOG = System.getLogger(FfmpegGrabLoop.class.getName());

    private final StreamId streamId;
    private final URI uri;
    private final Map<String, String> options;
    private final FfmpegSettings settings;
    private final boolean loop;
    private final boolean paced;
    private final Map<StreamId, AtomicInteger> consecutiveOpenFailures;
    private final SubmissionPublisher<VideoFrame> publisher;
    private final AtomicLong sequence = new AtomicLong();
    private final AtomicBoolean stopRequested = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final RealtimePacer pacer = new RealtimePacer();
    private volatile Thread grabThread;

    FfmpegGrabLoop(StreamId streamId, URI uri, Map<String, String> options, FfmpegSettings settings,
            Map<StreamId, AtomicInteger> consecutiveOpenFailures) {
        this.streamId = streamId;
        this.uri = uri;
        this.options = options;
        this.settings = settings;
        this.loop = booleanOption(options, FfmpegVideoSource.OPTION_LOOP, FfmpegVideoSource.DEFAULT_LOOP);
        this.paced = FfmpegVideoSource.PROTOCOL_FILE.equalsIgnoreCase(uri.getScheme());
        this.consecutiveOpenFailures = consecutiveOpenFailures;
        this.publisher = new SubmissionPublisher<>(ForkJoinPool.commonPool(), settings.publisherBufferCapacity());
    }

    Flow.Publisher<VideoFrame> publisher() {
        return publisher;
    }

    void start() {
        grabThread = new Thread(this::runGrabLoop, "rtsp-video-" + streamId.value());
        grabThread.setDaemon(true);
        grabThread.start();
    }

    private void runGrabLoop() {
        FFmpegFrameGrabber grabber = null;
        boolean errored = false;
        try {
            grabber = FfmpegGrabberOptions.newGrabber(uri, options, settings);
            grabber.start();
            // A successful connect/DESCRIBE is proof this stream is no longer stuck failing to
            // open at all (e.g. a dead RTSP path returning 404) -- reset the WARN-log attempt
            // counter below, mirroring MediamtxStreamPublisher.StreamState's own
            // reset-on-recovery convention (adapter-publish-hls).
            consecutiveOpenFailures.remove(streamId);
            while (!stopRequested.get()) {
                // grabImage(), not grab(): grab() also returns audio/data frames, and mp4
                // muxers interleave audio up to ~0.5s AHEAD of video -- pacing on those
                // look-ahead timestamps stalled the loop until wall-clock caught up with
                // the audio track, starving video down to ~2fps (observed with a real
                // 30fps clip carrying AAC). grabImage() skips non-video packets, so the
                // pacing timeline is the video track's own monotonic timestamps.
                Frame frame = grabber.grabImage();
                if (frame == null) {
                    if (loop && !stopRequested.get()) {
                        grabber.restart(); // stop() + start(): reopens the file from the beginning
                        pacer.reset(); // reset pacing baseline across the loop restart
                        continue;
                    }
                    break; // end of stream (e.g. a file source ran out) -- graceful completion
                }
                if (paced) {
                    pacer.paceTo(grabber.getTimestamp());
                }
                if (frame.image == null || frame.image.length == 0) {
                    continue; // audio/data-only frame: no pixel payload to publish
                }
                ByteBuffer copy = FrameConverter.copyBgr24(frame);
                VideoFrame videoFrame = new VideoFrame(streamId, sequence.getAndIncrement(), Instant.now(),
                        frame.imageWidth, frame.imageHeight, PixelFormat.BGR24, copy);
                // Latest-wins backpressure: never block capture for a slow subscriber;
                // when a subscriber's small buffer is full, the offered frame is dropped
                // instead of queuing capture indefinitely.
                publisher.offer(videoFrame, (subscriber, dropped) -> true);
            }
        } catch (Exception e) {
            errored = true;
            if (!stopRequested.get()) {
                // Unrecoverable grab failure: signal onError and stop producing frames. This is
                // the only place a dead/unreachable source (e.g. an RTSP DESCRIBE 404 against a
                // path nothing is publishing to) becomes visible anywhere but FFmpeg's own
                // stderr -- log it at WARN with the source and a local consecutive-failure count
                // before handing off to the caller (vision-application's SupervisedPublisher,
                // which retries this exact open() with a 1s->30s exponential backoff,
                // indefinitely, until it succeeds).
                int attempt = consecutiveOpenFailures
                        .computeIfAbsent(streamId, unused -> new AtomicInteger())
                        .incrementAndGet();
                LOG.log(System.Logger.Level.WARNING, () -> "Video source error for stream "
                        + streamId.value() + " at " + uri + " (consecutive failure " + attempt
                        + "); will be retried with backoff by the stream supervisor, dropping "
                        + "frames until it recovers", e);
                publisher.closeExceptionally(e);
            }
        } finally {
            releaseQuietly(grabber);
            if (!errored) {
                publisher.close();
            }
        }
    }

    void close() {
        if (closed.compareAndSet(false, true)) {
            stopRequested.set(true);
            Thread thread = grabThread;
            if (thread != null && thread != Thread.currentThread()) {
                thread.interrupt(); // best-effort; native grab() may not respond to this
                try {
                    thread.join(settings.closeJoinTimeout().toMillis());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            publisher.close();
        }
    }

    private static void releaseQuietly(FFmpegFrameGrabber grabber) {
        if (grabber == null) {
            return;
        }
        try {
            grabber.release();
        } catch (Exception ignored) {
            // best-effort cleanup; nothing more actionable if release fails
        }
    }

    /**
     * Lenient boolean option parsing, matching this module's existing idiom for other options:
     * missing/blank falls back to {@code defaultValue}, and so does anything that isn't
     * (case-insensitively) {@code "true"} or {@code "false"} -- a malformed value is never allowed
     * to crash stream setup.
     */
    private static boolean booleanOption(Map<String, String> options, String key, boolean defaultValue) {
        String raw = options.get(key);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        String trimmed = raw.trim();
        if ("true".equalsIgnoreCase(trimmed)) {
            return true;
        }
        if ("false".equalsIgnoreCase(trimmed)) {
            return false;
        }
        return defaultValue; // malformed: keep the default rather than guessing
    }
}
