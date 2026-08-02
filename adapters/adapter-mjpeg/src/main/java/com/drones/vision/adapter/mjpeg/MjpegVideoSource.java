package com.drones.vision.adapter.mjpeg;

import com.drones.vision.domain.model.PixelFormat;
import com.drones.vision.domain.model.StreamDescriptor;
import com.drones.vision.domain.model.StreamId;
import com.drones.vision.domain.model.VideoFrame;
import com.drones.vision.domain.port.out.VideoSourcePort;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Flow;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * {@link VideoSourcePort} implementation that ingests an MJPEG {@code
 * multipart/x-mixed-replace} HTTP stream — exactly what an ESP32-CAM (or
 * mjpg-streamer, or similar) serves. Supports {@link
 * StreamDescriptor#protocol()} {@code "mjpeg"} with an {@code http} or {@code
 * https} {@link StreamDescriptor#uri()}.
 *
 * <p>Recognized {@link StreamDescriptor#options()} keys:
 * <ul>
 *   <li>{@code timeout} — connect timeout in milliseconds, default {@link
 *       MjpegSettings#readTimeout()} (lenient parse: missing/blank/malformed
 *       values fall back to that default, same idiom as {@code adapter-rtsp}).
 *       Only bounds the initial TCP connect — once the streaming GET is
 *       under way, the connection is expected to stay open indefinitely, so
 *       nothing times out the body read itself.</li>
 * </ul>
 *
 * <p>The multipart body is parsed by hand via {@link MjpegStreamParser} — no
 * multipart/MIME library dependency on the read path, matching {@code
 * docs/CYCLES-PLAN.md} §5's "transparent, dependency-free" requirement. Each
 * extracted JPEG part is passed through unmodified ({@link PixelFormat#JPEG}
 * <b>passthrough</b> — the pipeline already knows how to handle JPEG frames,
 * see {@code adapter-simulation}'s {@code SimulatedVideoSource}); width/height
 * are read from the JPEG's own {@code SOF0}/{@code SOF2} marker via {@link
 * JpegDimensions} rather than decoding the image. A part whose dimensions
 * cannot be determined is skipped (never published, never fatal).
 *
 * <p>Each {@link #open(StreamId, StreamDescriptor)} call starts one dedicated
 * platform thread ({@code mjpeg-video-<id>}) running a blocking read loop
 * against a fresh {@link HttpClient}, feeding a per-stream {@link
 * SubmissionPublisher} with a small buffer and a drop-newest backpressure
 * policy, exactly like {@code adapter-rtsp}'s {@code FfmpegVideoSource} and
 * {@code adapter-simulation}'s {@code SimulatedVideoSource}. An unrecoverable
 * I/O failure (connection refused, stream reset, malformed multipart framing,
 * ...) calls {@link Flow.Subscriber#onError(Throwable)} once and lets the
 * loop end on its own — the port's contract for an unrecoverable source
 * failure; {@link #close(StreamId)} is the cooperative counterpart and is
 * idempotent.
 *
 * <p>Plain class with no framework dependency — instantiated directly by
 * {@code vision-app}'s wiring configuration.
 */
public final class MjpegVideoSource implements VideoSourcePort {

    private static final String PROTOCOL = "mjpeg";
    private static final String SCHEME_HTTP = "http";
    private static final String SCHEME_HTTPS = "https";

    static final String OPTION_TIMEOUT_MILLIS = "timeout";

    private final MjpegSettings settings;
    private final Map<StreamId, StreamRuntime> runtimes = new ConcurrentHashMap<>();

    public MjpegVideoSource() {
        this(MjpegSettings.defaults());
    }

    public MjpegVideoSource(MjpegSettings settings) {
        this.settings = Objects.requireNonNull(settings, "settings must not be null");
    }

    @Override
    public boolean supports(StreamDescriptor descriptor) {
        if (descriptor == null || !PROTOCOL.equals(descriptor.protocol())) {
            return false;
        }
        URI uri = descriptor.uri();
        if (uri == null) {
            return false;
        }
        String scheme = uri.getScheme();
        return SCHEME_HTTP.equalsIgnoreCase(scheme) || SCHEME_HTTPS.equalsIgnoreCase(scheme);
    }

    @Override
    public Flow.Publisher<VideoFrame> open(StreamId id, StreamDescriptor descriptor) {
        if (!supports(descriptor)) {
            throw new IllegalArgumentException("MjpegVideoSource does not support descriptor: " + descriptor);
        }
        StreamRuntime runtime = new StreamRuntime(id, descriptor.uri(), descriptor.options(), settings);
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

    /** Per-open runtime: a dedicated HTTP read thread feeding a {@link SubmissionPublisher}. */
    private static final class StreamRuntime {
        private final StreamId streamId;
        private final URI uri;
        private final long timeoutMillis;
        private final long closeJoinTimeoutMillis;
        private final SubmissionPublisher<VideoFrame> publisher;
        private final AtomicLong sequence = new AtomicLong();
        private final AtomicBoolean stopRequested = new AtomicBoolean(false);
        private final AtomicBoolean closed = new AtomicBoolean(false);
        private volatile Thread readThread;
        /** The live response body, if a connection is currently open; closing it unblocks a pending read. */
        private volatile InputStream currentBody;

        StreamRuntime(StreamId streamId, URI uri, Map<String, String> options, MjpegSettings settings) {
            this.streamId = streamId;
            this.uri = uri;
            this.timeoutMillis = longOption(options, OPTION_TIMEOUT_MILLIS, settings.readTimeout().toMillis());
            this.closeJoinTimeoutMillis = settings.closeJoinTimeout().toMillis();
            this.publisher = new SubmissionPublisher<>(ForkJoinPool.commonPool(), settings.publisherBufferCapacity());
        }

        void start() {
            readThread = new Thread(this::runReadLoop, "mjpeg-video-" + streamId.value());
            readThread.setDaemon(true);
            readThread.start();
        }

        private void runReadLoop() {
            boolean errored = false;
            try {
                HttpClient client = HttpClient.newBuilder()
                        // HTTP/1.1 only: the default HTTP/2 preference adds "Upgrade: h2c" +
                        // "HTTP2-Settings" headers to plain-http requests, which embedded MJPEG
                        // servers (ESP32-CAM et al.) reject with 400 Bad Request.
                        .version(HttpClient.Version.HTTP_1_1)
                        .connectTimeout(Duration.ofMillis(timeoutMillis))
                        .build();
                HttpRequest request = HttpRequest.newBuilder(uri).GET().build();
                HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
                if (response.statusCode() / 100 != 2) {
                    throw new IOException("MJPEG source returned HTTP " + response.statusCode() + " for " + uri);
                }
                String contentType = response.headers().firstValue("Content-Type").orElse(null);
                MjpegStreamParser parser = MjpegStreamParser.forContentType(contentType);

                InputStream raw = response.body();
                currentBody = raw;
                try (InputStream body = new BufferedInputStream(raw)) {
                    byte[] jpeg;
                    while (!stopRequested.get() && (jpeg = parser.nextPart(body)) != null) {
                        int[] dimensions = JpegDimensions.parse(jpeg);
                        if (dimensions == null) {
                            continue; // unparsable dimensions: skip this part, never fatal
                        }
                        VideoFrame frame = new VideoFrame(streamId, sequence.getAndIncrement(), Instant.now(),
                                dimensions[0], dimensions[1], PixelFormat.JPEG, ByteBuffer.wrap(jpeg));
                        // Latest-wins backpressure: never block capture for a slow subscriber.
                        publisher.offer(frame, (subscriber, dropped) -> true);
                    }
                }
            } catch (Exception e) {
                errored = true;
                if (!stopRequested.get()) {
                    // Unrecoverable I/O error: signal onError and stop producing frames -- this
                    // loop simply ending is this adapter's self-close, per VideoSourcePort's contract.
                    publisher.closeExceptionally(e);
                }
            } finally {
                currentBody = null;
                if (!errored) {
                    publisher.close();
                }
            }
        }

        void close() {
            if (closed.compareAndSet(false, true)) {
                stopRequested.set(true);
                closeQuietly(currentBody); // unblock a read that is currently blocked on the socket
                Thread thread = readThread;
                if (thread != null && thread != Thread.currentThread()) {
                    thread.interrupt(); // best-effort; a blocked socket read may not respond to this alone
                    try {
                        thread.join(closeJoinTimeoutMillis);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                publisher.close();
            }
        }

        private static void closeQuietly(InputStream in) {
            if (in == null) {
                return;
            }
            try {
                in.close();
            } catch (IOException ignored) {
                // best-effort cleanup; nothing more actionable if close fails
            }
        }

        /**
         * Lenient long option parsing, matching this module's other options: missing/blank
         * falls back to {@code defaultValue}, and so does anything non-positive or unparsable —
         * a malformed value is never allowed to crash stream setup.
         */
        private static long longOption(Map<String, String> options, String key, long defaultValue) {
            String raw = options.get(key);
            if (raw == null || raw.isBlank()) {
                return defaultValue;
            }
            try {
                long value = Long.parseLong(raw.trim());
                return value > 0 ? value : defaultValue;
            } catch (NumberFormatException e) {
                return defaultValue;
            }
        }
    }
}
