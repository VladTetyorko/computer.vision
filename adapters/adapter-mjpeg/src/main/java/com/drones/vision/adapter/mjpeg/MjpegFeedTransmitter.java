package com.drones.vision.adapter.mjpeg;

import com.drones.vision.domain.model.FeedId;
import com.drones.vision.domain.model.FeedSpec;
import com.drones.vision.domain.model.StreamDescriptor;
import com.drones.vision.domain.port.out.FeedTransmitterPort;

import com.sun.net.httpserver.HttpExchange;

import java.io.Closeable;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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
 *   <li>{@code loop} — {@code "true"}/{@code "false"}, default {@link
 *       MjpegSettings.Transmit#loop()} (malformed values fall back to that
 *       default, same lenient idiom as {@code adapter-rtsp}'s {@code
 *       RtspFeedTransmitter}, for the same reason: a transmitted simulation
 *       feed exists to be watched/rehearsed indefinitely).</li>
 * </ul>
 *
 * <h2>HTTP server design</h2>
 * One shared HTTP server (owned by {@link MjpegHttpServerHost}) is used
 * across every feed this <b>instance</b> transmits, bound to {@link
 * MjpegSettings.Transmit#bindHost()} on an ephemeral port and started
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
 * {@link MjpegViewerSession} (its own {@code FFmpegFrameGrabber} against the
 * source file, its own decode/encode/pace loop) for the lifetime of that one
 * HTTP response (KISS — no shared decode state, no fan-out; a real N-viewer
 * deployment would want a single shared decode loop, out of scope here).
 * Each viewer's grab loop temporarily renames its handling thread to {@code
 * mjpeg-feed-<id>-<n>} for the duration of the request (restored to the
 * pool's own name in a {@code finally} block once the connection ends) so a
 * thread dump — or a test asserting no {@code mjpeg-} threads remain after
 * teardown — reflects only genuinely active per-feed work, never the
 * underlying HTTP dispatch pool's own idle worker threads.
 *
 * <p>Plain class with no Spring dependency — instantiated directly by
 * {@code vision-app}'s wiring configuration.
 */
public final class MjpegFeedTransmitter implements FeedTransmitterPort, Closeable {

    private static final System.Logger LOG = System.getLogger(MjpegFeedTransmitter.class.getName());

    private static final String PROTOCOL_MJPEG = "mjpeg";
    private static final String PROTOCOL_FILE = "file";
    private static final String CONTEXT_PREFIX = "/feed-";

    static final String OPTION_LOOP = "loop";

    private final MjpegSettings settings;
    private final MjpegHttpServerHost serverHost;
    private final Map<FeedId, FeedRegistration> feeds = new ConcurrentHashMap<>();

    public MjpegFeedTransmitter() {
        this(MjpegSettings.defaults());
    }

    public MjpegFeedTransmitter(MjpegSettings settings) {
        this.settings = Objects.requireNonNull(settings, "settings must not be null");
        this.serverHost = new MjpegHttpServerHost(settings.transmit());
        MjpegViewerSession.ensureQuietLogging();
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

        boolean loop = booleanOption(spec.options(), OPTION_LOOP, settings.transmit().loop());
        String contextPath = CONTEXT_PREFIX + id.value();

        FeedRegistration registration = new FeedRegistration(id, sourcePath, loop,
                settings.transmit().jpegQuality(), settings.transmit().viewerJoinTimeout().toMillis());
        FeedRegistration previous = feeds.put(id, registration);
        if (previous != null) {
            previous.close(); // defensive: an id must not have two live feeds
        }
        int port = serverHost.ensureStartedPort();
        serverHost.createContext(contextPath, registration::handle);

        URI uri = URI.create("http://" + settings.transmit().bindHost() + ":" + port + contextPath);
        return new StreamDescriptor(PROTOCOL_MJPEG, uri, Map.of());
    }

    @Override
    public void stop(FeedId id) {
        FeedRegistration registration = feeds.remove(id);
        if (registration == null) {
            return;
        }
        registration.close();
        serverHost.removeContext(CONTEXT_PREFIX + id.value());
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
        serverHost.close();
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

    /** Per-feed registration: an HTTP context handler that runs one {@link MjpegViewerSession} per connected viewer. */
    private static final class FeedRegistration {
        private final FeedId feedId;
        private final Path sourcePath;
        private final boolean loop;
        private final float jpegQuality;
        private final long viewerJoinTimeoutMillis;
        private final Set<HttpExchange> activeExchanges = ConcurrentHashMap.newKeySet();
        private final Set<Thread> activeThreads = ConcurrentHashMap.newKeySet();
        private final AtomicInteger viewerSequence = new AtomicInteger();
        private final AtomicBoolean closed = new AtomicBoolean(false);

        FeedRegistration(FeedId feedId, Path sourcePath, boolean loop, float jpegQuality, long viewerJoinTimeoutMillis) {
            this.feedId = feedId;
            this.sourcePath = sourcePath;
            this.loop = loop;
            this.jpegQuality = jpegQuality;
            this.viewerJoinTimeoutMillis = viewerJoinTimeoutMillis;
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
                exchange.getResponseHeaders().set("Content-Type", MjpegViewerSession.CONTENT_TYPE);
                exchange.sendResponseHeaders(200, 0); // 0 = streamed/chunked, length not known up front
                new MjpegViewerSession(sourcePath, loop, jpegQuality).stream(exchange.getResponseBody(), closed::get);
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
                            thread.join(viewerJoinTimeoutMillis);
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
    }
}
