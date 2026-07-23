package com.drones.vision.adapter.mjpeg;

import com.drones.vision.domain.model.FeedId;
import com.drones.vision.domain.model.FeedSpec;
import com.drones.vision.domain.model.StreamDescriptor;
import com.drones.vision.domain.port.out.FeedTransmitterPort;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.Java2DFrameConverter;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@link FeedTransmitterPort} implementation that serves a local video file
 * as an MJPEG {@code multipart/x-mixed-replace} HTTP stream — a tiny
 * in-process stand-in for a real MJPEG camera (e.g. an ESP32-CAM), so this
 * module's own {@link MjpegVideoSource} (or any real MJPEG client) can
 * ingest it exactly like real hardware. Supports {@link FeedSpec#protocol()}
 * {@code "mjpeg"} with a {@link FeedSpec#source()} whose URI scheme is
 * itself {@code file}.
 *
 * <p>This is the TX (transmit) half of the RX/TX doctrine in {@code
 * docs/CYCLES-PLAN.md} §0/§5: <b>simulation infrastructure, not egress</b> —
 * unrelated to {@code StreamPublisherPort} (viewer-facing HLS egress).
 *
 * <h2>Recognized {@link FeedSpec#options()}</h2>
 * <ul>
 *   <li>{@code loop} — {@code "true"}/{@code "false"}, default <b>{@code
 *       true}</b> (malformed values fall back to the default, same lenient
 *       idiom as {@code adapter-rtsp}'s {@code RtspFeedTransmitter}, for the
 *       same reason: a transmitted simulation feed exists to be
 *       watched/rehearsed indefinitely).</li>
 * </ul>
 *
 * <h2>HTTP server design</h2>
 * One {@link HttpServer} is shared across every feed this <b>instance</b>
 * transmits, bound to an ephemeral {@code 127.0.0.1} port and started
 * lazily on the first {@link #start(FeedId, FeedSpec)} call — not one
 * server per feed. This keeps port usage minimal (a real deployment might
 * simulate many feeds) at the cost of every feed sharing one HTTP accept
 * loop, which is a non-issue at this scale (KISS). Each feed gets its own
 * {@code /feed-<id>} context on that shared server; {@link #stop(FeedId)}
 * removes only that feed's context and stops its in-flight viewers — it
 * deliberately does <b>not</b> stop the shared server itself (a subsequent
 * {@link #start(FeedId, FeedSpec)} call may need it again). {@link #close()}
 * is the only operation that actually shuts the server down, releasing its
 * port; it is safe to call even if feeds are still active (it stops them
 * first) and safe to call more than once.
 *
 * <h2>Concurrent viewers</h2>
 * Multiple simultaneous {@code GET}s against the same feed's context are
 * each handled independently: every accepted connection opens its own
 * {@link FFmpegFrameGrabber} against the source file and runs its own
 * decode/encode/pace loop for the lifetime of that one HTTP response (KISS
 * — no shared decode state, no fan-out; a real N-viewer deployment would
 * want a single shared decode loop, out of scope here). Each viewer's grab
 * loop temporarily renames its handling thread to {@code
 * mjpeg-feed-<id>-<n>} for the duration of the request (restored to the
 * pool's own name in a {@code finally} block once the connection ends) so a
 * thread dump — or a test asserting no {@code mjpeg-} threads remain after
 * teardown — reflects only genuinely active per-feed work, never the
 * underlying HTTP dispatch pool's own idle worker threads.
 *
 * <p><b>Real-time pacing</b> duplicates the timestamp-delta approach of
 * {@code adapter-rtsp}'s {@code FfmpegVideoSource}/{@code
 * RtspFeedTransmitter} (deliberately not shared — adapters must not depend
 * on each other, per {@code CLAUDE.md}) so each viewer's JPEG parts are
 * produced at the source file's own native frame rate rather than as fast
 * as disk I/O allows.
 *
 * <p>Plain class with no Spring dependency — instantiated directly by
 * {@code vision-app}'s wiring configuration.
 */
public final class MjpegFeedTransmitter implements FeedTransmitterPort, Closeable {

    private static final System.Logger LOG = System.getLogger(MjpegFeedTransmitter.class.getName());

    private static final String PROTOCOL_MJPEG = "mjpeg";
    private static final String PROTOCOL_FILE = "file";
    private static final String CONTEXT_PREFIX = "/feed-";
    private static final String BOUNDARY_TOKEN = "visionmjpegboundary";

    static final String OPTION_LOOP = "loop";
    static final boolean DEFAULT_LOOP = true; // opposite default of MjpegVideoSource, same rationale as RtspFeedTransmitter

    private static final long VIEWER_JOIN_TIMEOUT_MILLIS = 5_000L;

    private final Object serverLock = new Object();
    private volatile HttpServer server;
    private volatile ExecutorService serverExecutor;
    private final Map<FeedId, FeedRegistration> feeds = new ConcurrentHashMap<>();

    public MjpegFeedTransmitter() {
        ensureQuietLogging();
    }

    // -- native log quieting --------------------------------------------------
    // Duplicated (not shared) from adapter-rtsp's FfmpegVideoSource/RtspFeedTransmitter and
    // adapter-publish-hls's MediamtxStreamPublisher: per CLAUDE.md's dependency rule, adapters
    // must not depend on each other, so this small guard is intentionally copy-pasted rather
    // than factored into a shared utility module. See FfmpegVideoSource's MODULE.md/javadoc for
    // the empirical reasoning behind plain av_log_set_level over FFmpegLogCallback.set().
    private static boolean quietLoggingConfigured = false;

    /**
     * Idempotent; lowers FFmpeg's native log threshold to {@code AV_LOG_ERROR} so grabber
     * start/stop no longer dumps INFO-level banners to stdout/stderr. Safe to call repeatedly.
     */
    static synchronized void ensureQuietLogging() {
        if (quietLoggingConfigured) {
            return;
        }
        // ERROR, not WARNING: swscale's per-frame "deprecated pixel format used"
        // warning on yuvj-tagged inputs is benign, unavoidable via JavaCV's
        // high-level API, and drowns real logs. See FfmpegVideoSource (adapter-rtsp).
        avutil.av_log_set_level(avutil.AV_LOG_ERROR);
        quietLoggingConfigured = true;
    }

    @Override
    public boolean supports(FeedSpec spec) {
        if (spec == null || !PROTOCOL_MJPEG.equals(spec.protocol())) {
            return false;
        }
        URI source = spec.source();
        return source != null && PROTOCOL_FILE.equalsIgnoreCase(source.getScheme());
    }

    @Override
    public StreamDescriptor start(FeedId id, FeedSpec spec) {
        if (id == null) {
            throw new IllegalArgumentException("id must not be null");
        }
        if (!supports(spec)) {
            throw new IllegalArgumentException("MjpegFeedTransmitter does not support spec: " + spec);
        }

        Path sourcePath = Paths.get(spec.source());
        if (!Files.isRegularFile(sourcePath) || !Files.isReadable(sourcePath)) {
            throw new IllegalArgumentException("FeedSpec source is not a readable file: " + sourcePath);
        }

        boolean loop = booleanOption(spec.options(), OPTION_LOOP, DEFAULT_LOOP);
        HttpServer httpServer = ensureServerStarted();
        String contextPath = CONTEXT_PREFIX + id.value();

        FeedRegistration registration = new FeedRegistration(id, sourcePath, loop);
        FeedRegistration previous = feeds.put(id, registration);
        if (previous != null) {
            previous.close(); // defensive: an id must not have two live feeds
        }
        httpServer.createContext(contextPath, registration::handle);

        int port = httpServer.getAddress().getPort();
        URI uri = URI.create("http://127.0.0.1:" + port + contextPath);
        return new StreamDescriptor(PROTOCOL_MJPEG, uri, Map.of());
    }

    @Override
    public void stop(FeedId id) {
        FeedRegistration registration = feeds.remove(id);
        if (registration == null) {
            return;
        }
        registration.close();
        HttpServer httpServer = server;
        if (httpServer != null) {
            httpServer.removeContext(CONTEXT_PREFIX + id.value());
        }
    }

    /**
     * Stops every active feed and shuts down the shared HTTP server (and its dispatch thread
     * pool), releasing its port. {@link #stop(FeedId)} alone never does this (see class
     * javadoc) — only this method does. Idempotent; safe to call even with no feeds active.
     */
    @Override
    public void close() {
        for (FeedId id : List.copyOf(feeds.keySet())) {
            stop(id);
        }
        synchronized (serverLock) {
            if (server != null) {
                server.stop(0);
                server = null;
            }
            if (serverExecutor != null) {
                serverExecutor.shutdownNow();
                serverExecutor = null;
            }
        }
    }

    private HttpServer ensureServerStarted() {
        HttpServer existing = server;
        if (existing != null) {
            return existing;
        }
        synchronized (serverLock) {
            if (server == null) {
                try {
                    HttpServer created = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                    // Default (unprefixed) thread factory naming deliberately, NOT "mjpeg-*" -- these
                    // dispatch threads may legitimately outlive stop(id) (see class javadoc); only
                    // the per-viewer grab-loop rename (see FeedRegistration#handle) uses that prefix,
                    // so a "no mjpeg- threads left" check after stop()+close() is meaningful.
                    ExecutorService executor = Executors.newCachedThreadPool();
                    created.setExecutor(executor);
                    created.start();
                    server = created;
                    serverExecutor = executor;
                } catch (IOException e) {
                    throw new IllegalStateException("Failed to start the MJPEG feed HTTP server", e);
                }
            }
            return server;
        }
    }

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

    /** Per-feed registration: an HTTP context handler that runs one grab loop per connected viewer. */
    private static final class FeedRegistration {
        private final FeedId feedId;
        private final Path sourcePath;
        private final boolean loop;
        private final Set<HttpExchange> activeExchanges = ConcurrentHashMap.newKeySet();
        private final Set<Thread> activeThreads = ConcurrentHashMap.newKeySet();
        private final AtomicInteger viewerSequence = new AtomicInteger();
        private final AtomicBoolean closed = new AtomicBoolean(false);

        FeedRegistration(FeedId feedId, Path sourcePath, boolean loop) {
            this.feedId = feedId;
            this.sourcePath = sourcePath;
            this.loop = loop;
        }

        void handle(HttpExchange exchange) throws IOException {
            if (closed.get()) {
                exchange.sendResponseHeaders(410, -1); // Gone: this feed has already been stopped
                exchange.close();
                return;
            }
            Thread thread = Thread.currentThread();
            String originalName = thread.getName();
            thread.setName("mjpeg-feed-" + feedId.value() + "-" + viewerSequence.incrementAndGet());
            activeExchanges.add(exchange);
            activeThreads.add(thread);
            try {
                streamToExchange(exchange);
            } catch (IOException e) {
                // Client disconnected, or the exchange/thread was closed/interrupted by stop() --
                // normal termination of this one viewer's stream, nothing to escalate.
                LOG.log(System.Logger.Level.DEBUG, () -> "MJPEG viewer stream ended for feed " + feedId.value(), e);
            } finally {
                activeThreads.remove(thread);
                activeExchanges.remove(exchange);
                thread.setName(originalName);
                closeQuietly(exchange);
            }
        }

        private void streamToExchange(HttpExchange exchange) throws IOException {
            exchange.getResponseHeaders().set("Content-Type", "multipart/x-mixed-replace; boundary=" + BOUNDARY_TOKEN);
            exchange.sendResponseHeaders(200, 0); // 0 = streamed/chunked, length not known up front
            OutputStream out = exchange.getResponseBody();
            Java2DFrameConverter converter = new Java2DFrameConverter();

            FFmpegFrameGrabber grabber = null;
            try {
                grabber = new FFmpegFrameGrabber(sourcePath.toString());
                grabber.start();

                // Real-time pacing (duplicated from FfmpegVideoSource/RtspFeedTransmitter, see
                // class javadoc): -1 means "no previous frame yet" -- the next grabbed frame sets
                // the baseline without sleeping, whether the very first frame or after a restart.
                long pacingBaselineTimestampMicros = -1;
                long pacingBaselineWallNanos = 0;
                while (!closed.get()) {
                    // grabImage(), not grab(): audio frames' look-ahead timestamps would
                    // stall the pacing sleep (see adapter-rtsp FfmpegVideoSource's grab loop).
                    Frame frame = grabber.grabImage();
                    if (frame == null) {
                        if (loop && !closed.get()) {
                            grabber.restart(); // stop() + start(): reopens the file from the beginning
                            pacingBaselineTimestampMicros = -1;
                            continue;
                        }
                        break; // end of stream and not looping -- graceful completion
                    }
                    long timestampMicros = grabber.getTimestamp();
                    if (pacingBaselineTimestampMicros >= 0) {
                        long targetDeltaMicros = timestampMicros - pacingBaselineTimestampMicros;
                        long elapsedMicros = (System.nanoTime() - pacingBaselineWallNanos) / 1_000L;
                        sleepMicros(targetDeltaMicros - elapsedMicros);
                    }
                    pacingBaselineTimestampMicros = timestampMicros;
                    pacingBaselineWallNanos = System.nanoTime();

                    if (frame.image == null || frame.image.length == 0) {
                        continue; // audio/data-only frame: nothing to encode
                    }
                    BufferedImage image = converter.convert(frame);
                    if (image == null) {
                        continue;
                    }
                    writePart(out, encodeJpeg(image));
                }
            } finally {
                releaseQuietly(grabber);
            }
        }

        private static void writePart(OutputStream out, byte[] jpeg) throws IOException {
            String header = "--" + BOUNDARY_TOKEN + "\r\n"
                    + "Content-Type: image/jpeg\r\n"
                    + "Content-Length: " + jpeg.length + "\r\n"
                    + "\r\n";
            out.write(header.getBytes(StandardCharsets.US_ASCII));
            out.write(jpeg);
            out.write('\r');
            out.write('\n');
            out.flush();
        }

        private static byte[] encodeJpeg(BufferedImage image) throws IOException {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (!ImageIO.write(image, "jpg", out)) {
                throw new IOException("No JPEG writer available for MJPEG feed encoding");
            }
            return out.toByteArray();
        }

        /**
         * Idempotent: closes every in-flight viewer's exchange (unblocking any pending write) and
         * interrupts (unblocking any pending pacing sleep) then bounded-joins every active
         * viewer thread. Does not touch the shared HTTP server itself -- see the enclosing
         * class's javadoc.
         */
        void close() {
            if (closed.compareAndSet(false, true)) {
                for (HttpExchange exchange : List.copyOf(activeExchanges)) {
                    closeQuietly(exchange);
                }
                List<Thread> threads = List.copyOf(activeThreads);
                for (Thread thread : threads) {
                    if (thread != Thread.currentThread()) {
                        thread.interrupt();
                    }
                }
                for (Thread thread : threads) {
                    if (thread != Thread.currentThread()) {
                        try {
                            thread.join(VIEWER_JOIN_TIMEOUT_MILLIS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                }
            }
        }

        private static void closeQuietly(HttpExchange exchange) {
            try {
                exchange.close();
            } catch (Exception ignored) {
                // best-effort cleanup; nothing more actionable if close fails
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

        /** Sleeps the given microsecond duration; clamps negative/zero to a no-op. */
        private static void sleepMicros(long micros) {
            if (micros <= 0) {
                return;
            }
            try {
                TimeUnit.MICROSECONDS.sleep(micros);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
