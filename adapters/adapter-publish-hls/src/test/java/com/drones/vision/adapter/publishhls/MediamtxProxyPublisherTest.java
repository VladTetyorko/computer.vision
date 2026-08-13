package com.drones.vision.adapter.publishhls;

import com.drones.vision.kernel.Capability;
import com.drones.vision.warehouse.domain.model.Device;
import com.drones.vision.kernel.DeviceId;
import com.drones.vision.perception.domain.model.PixelFormat;
import com.drones.vision.kernel.StreamDescriptor;
import com.drones.vision.kernel.StreamId;
import com.drones.vision.perception.domain.model.VideoFrame;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link MediamtxProxyPublisher} against an in-process HTTP server standing in for
 * mediamtx's Control API — real sockets, no mocked {@link java.net.http.HttpClient}, same idiom as
 * {@link MediamtxControlApiTest}. A real end-to-end run against a real mediamtx container (path
 * created, source dialed, readiness genuinely reached, path deleted) is {@link
 * MediamtxProxyPublisherDockerIntegrationTest} (docker-gated).
 */
class MediamtxProxyPublisherTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void streamStartedSucceedsOnceMediamtxReportsReady() throws IOException {
        AtomicInteger readinessChecks = new AtomicInteger();
        server = startServer(exchange -> {
            String path = exchange.getRequestURI().getPath();
            if (path.contains("/config/paths/add/")) {
                respond(exchange, 200, "{\"status\":\"ok\"}");
            } else if (path.contains("/paths/get/")) {
                boolean ready = readinessChecks.incrementAndGet() >= 3;
                respond(exchange, 200, "{\"ready\":" + ready + "}");
            } else {
                respond(exchange, 404, "{\"status\":\"error\",\"error\":\"path not found\"}");
            }
        });
        MediamtxProxyPublisher publisher = proxyPublisher(baseUri(server), MediamtxProxySettings.defaults());

        assertDoesNotThrow(() -> publisher.streamStarted(StreamId.random(), rtspDevice()));

        assertTrue(readinessChecks.get() >= 3);
    }

    @Test
    void streamStartedThrowsADiagnosableExceptionWhenReadinessNeverArrivesWithinTheTimeout() throws IOException {
        server = startServer(exchange -> {
            String path = exchange.getRequestURI().getPath();
            if (path.contains("/config/paths/add/")) {
                respond(exchange, 200, "{\"status\":\"ok\"}");
            } else {
                respond(exchange, 200, "{\"ready\":false}");
            }
        });
        MediamtxProxySettings settings = new MediamtxProxySettings("automatic", Duration.ofMillis(300), false, null, null);
        MediamtxProxyPublisher publisher = proxyPublisher(baseUri(server), settings);

        MediamtxControlApiException exception = assertThrows(MediamtxControlApiException.class,
                () -> publisher.streamStarted(StreamId.random(), rtspDevice()));

        assertTrue(exception.getMessage().contains("never became ready"),
                "expected a diagnosable readiness-timeout message, got: " + exception.getMessage());
    }

    /**
     * D10's own escape hatch: with {@code sourceOnDemand=true} mediamtx does not dial the camera
     * until a reader connects, so polling readiness at start time would only ever time out — this
     * asserts the poll is skipped entirely rather than failing every on-demand start call.
     */
    @Test
    void streamStartedSkipsTheReadinessPollWhenSourceOnDemandIsEnabled() throws IOException {
        AtomicInteger readinessChecks = new AtomicInteger();
        server = startServer(exchange -> {
            String path = exchange.getRequestURI().getPath();
            if (path.contains("/config/paths/add/")) {
                respond(exchange, 200, "{\"status\":\"ok\"}");
            } else {
                readinessChecks.incrementAndGet();
                respond(exchange, 200, "{\"ready\":false}");
            }
        });
        MediamtxProxySettings settings = new MediamtxProxySettings("automatic", Duration.ofSeconds(10), true, null, null);
        MediamtxProxyPublisher publisher = proxyPublisher(baseUri(server), settings);

        assertDoesNotThrow(() -> publisher.streamStarted(StreamId.random(), rtspDevice()));

        assertEquals(0, readinessChecks.get(), "an on-demand path must never be polled for readiness at start time");
    }

    @Test
    void streamStartedThrowsWhenTheControlApiRejectsAuthentication() throws IOException {
        server = startServer(exchange -> respond(exchange, 401, "{\"status\":\"error\",\"error\":\"authentication error\"}"));
        MediamtxProxyPublisher publisher = proxyPublisher(baseUri(server), MediamtxProxySettings.defaults());

        MediamtxControlApiException exception = assertThrows(MediamtxControlApiException.class,
                () -> publisher.streamStarted(StreamId.random(), rtspDevice()));

        assertTrue(exception.getMessage().toLowerCase(Locale.ROOT).contains("authentication"));
    }

    @Test
    void publishIsANoOpAndNeverCallsMediamtx() throws IOException {
        AtomicInteger requests = new AtomicInteger();
        server = startServer(exchange -> {
            requests.incrementAndGet();
            respond(exchange, 200, "{\"status\":\"ok\"}");
        });
        MediamtxProxyPublisher publisher = proxyPublisher(baseUri(server), MediamtxProxySettings.defaults());
        StreamId streamId = StreamId.random();
        VideoFrame frame = new VideoFrame(streamId, 0L, Instant.now(), 8, 8, PixelFormat.BGR24,
                ByteBuffer.wrap(new byte[8 * 8 * 3]));

        assertDoesNotThrow(() -> publisher.publish(streamId, frame));

        assertEquals(0, requests.get());
    }

    @Test
    void streamEndedDeletesThePathAndNeverThrowsEvenWhenMediamtxFails() throws IOException {
        server = startServer(exchange -> respond(exchange, 500, "{\"status\":\"error\",\"error\":\"boom\"}"));
        MediamtxProxyPublisher publisher = proxyPublisher(baseUri(server), MediamtxProxySettings.defaults());

        assertDoesNotThrow(() -> publisher.streamEnded(StreamId.random()));
    }

    @Test
    void streamStartedAndStreamEndedAreNoOpsForANullStreamId() {
        MediamtxProxyPublisher publisher = proxyPublisher(URI.create("http://127.0.0.1:1"), MediamtxProxySettings.defaults());

        assertDoesNotThrow(() -> publisher.streamStarted(null, rtspDevice()));
        assertDoesNotThrow(() -> publisher.streamEnded(null));
    }

    /** D2: the mediamtx path name is exactly {@code streamId.value()} — same shape {@link MediamtxUrls} builds. */
    @Test
    void viewUrlAndWhepUrlDelegateToTheSameSharedUrlHelpersAsMediamtxStreamPublisher() {
        MediamtxProxyPublisher publisher = new MediamtxProxyPublisher(URI.create("http://localhost:19997"),
                URI.create("http://localhost:8888"), URI.create("http://localhost:8889"),
                URI.create("http://localhost:19996"), MediamtxProxySettings.defaults());
        StreamId streamId = StreamId.of("11111111-1111-1111-1111-111111111111");

        assertEquals(Optional.of(URI.create("http://localhost:8888/11111111-1111-1111-1111-111111111111/index.m3u8")),
                publisher.viewUrl(streamId));
        assertEquals(Optional.of(URI.create("http://localhost:8889/11111111-1111-1111-1111-111111111111/whep")),
                publisher.whepUrl(streamId));
        assertEquals(Optional.empty(), publisher.viewUrl(null));
        assertEquals(Optional.empty(), publisher.whepUrl(null));
    }

    @Test
    void playbackUrlIsEmptyWhenUnconfiguredAndPresentWhenConfigured() {
        MediamtxProxyPublisher withoutPlayback = new MediamtxProxyPublisher(URI.create("http://localhost:19997"),
                URI.create("http://localhost:8888"), URI.create("http://localhost:8889"), null,
                MediamtxProxySettings.defaults());
        MediamtxProxyPublisher withPlayback = new MediamtxProxyPublisher(URI.create("http://localhost:19997"),
                URI.create("http://localhost:8888"), URI.create("http://localhost:8889"),
                URI.create("http://localhost:19996"), MediamtxProxySettings.defaults());
        StreamId streamId = StreamId.of("11111111-1111-1111-1111-111111111111");
        Instant start = Instant.parse("2026-01-15T10:00:00Z");

        assertEquals(Optional.empty(), withoutPlayback.playbackUrl(streamId, start, Duration.ofSeconds(30)));
        assertEquals(Optional.of(URI.create("http://localhost:19996/get"
                        + "?path=11111111-1111-1111-1111-111111111111&start=2026-01-15T10:00:00Z&duration=30")),
                withPlayback.playbackUrl(streamId, start, Duration.ofSeconds(30)));
    }

    @Test
    void proxiesSourceIsAlwaysTrueRegardlessOfDevice() {
        MediamtxProxyPublisher publisher = proxyPublisher(URI.create("http://127.0.0.1:1"), MediamtxProxySettings.defaults());

        assertTrue(publisher.proxiesSource(rtspDevice()));
        assertTrue(publisher.proxiesSource(null));
    }

    private static MediamtxProxyPublisher proxyPublisher(URI apiBase, MediamtxProxySettings settings) {
        return new MediamtxProxyPublisher(apiBase, URI.create("http://localhost:8888"),
                URI.create("http://localhost:8889"), null, settings);
    }

    private static Device rtspDevice() {
        return new Device(DeviceId.random(), "camera-1", Set.of(Capability.VIDEO),
                new StreamDescriptor("rtsp", URI.create("rtsp://192.168.1.50:554/onvif1"), Map.of()));
    }

    private static HttpServer startServer(HttpHandler handler) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", handler);
        server.start();
        return server;
    }

    private static URI baseUri(HttpServer server) {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
