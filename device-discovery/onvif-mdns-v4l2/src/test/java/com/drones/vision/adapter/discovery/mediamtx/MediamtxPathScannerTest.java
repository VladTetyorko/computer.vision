package com.drones.vision.adapter.discovery.mediamtx;

import com.drones.vision.warehouse.domain.model.DiscoveredDevice;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real loopback {@link HttpServer} integration tests for {@link MediamtxPathScanner} -- a fake
 * server stands in for mediamtx's Control API, per this repo's "prefer real loopback over mocks"
 * convention, mirroring {@code OnvifDeviceClientTest}'s style. No docker/external prerequisite;
 * nothing here is gated.
 */
class MediamtxPathScannerTest {

    private static final URI RTSP_BASE = URI.create("rtsp://127.0.0.1:8554");
    private static final String PREFIX = "ingest/";

    private HttpServer server;
    private String baseUrl;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (var responseBody = exchange.getResponseBody()) {
            responseBody.write(bytes);
        }
    }

    private MediamtxPathScanner scanner() {
        return new MediamtxPathScanner(new MediamtxScannerSettings(URI.create(baseUrl), RTSP_BASE, PREFIX));
    }

    // -- happy path ------------------------------------------------------

    @Test
    void reportsOnlyReadyPathsUnderThePrefix() throws IOException {
        String body = "{\"itemCount\":4,\"pageCount\":1,\"items\":["
                + pathItem("ingest/rover-abc123", true, "rtspSession", 0) + ","
                + pathItem("ingest/cam-def456", true, "rtspSession", 0) + ","
                + pathItem("stream/vision-own-output", true, "rtspSession", 0) + ","
                + pathItem("ingest/notready-xyz", false, "rtspSource", 0)
                + "]}";
        server.createContext("/v3/paths/list", exchange -> respond(exchange, 200, body));
        server.start();

        List<DiscoveredDevice> found = scanner().scan(Duration.ofSeconds(5));

        assertEquals(2, found.size(), "expected only the two ready ingest/ paths: " + found);
        assertEquals(Map.of("rover-abc123", URI.create("rtsp://127.0.0.1:8554/ingest/rover-abc123"),
                "cam-def456", URI.create("rtsp://127.0.0.1:8554/ingest/cam-def456")),
                found.stream().collect(Collectors.toMap(DiscoveredDevice::name, DiscoveredDevice::address)));
        for (DiscoveredDevice device : found) {
            assertEquals("mediamtx", device.method());
            assertEquals("rtsp", device.suggestedStream().protocol());
            assertEquals(device.address(), device.suggestedStream().uri());
        }
    }

    @Test
    void detailsCarryThePathSourceTypeAndReaderCount() throws IOException {
        String body = "{\"items\":[" + pathItem("ingest/cam-def456", true, "rtspSession", 2) + "]}";
        server.createContext("/v3/paths/list", exchange -> respond(exchange, 200, body));
        server.start();

        List<DiscoveredDevice> found = scanner().scan(Duration.ofSeconds(5));

        assertEquals(1, found.size());
        Map<String, String> details = found.get(0).details();
        assertEquals("ingest/cam-def456", details.get("path"));
        assertEquals("rtspSession", details.get("sourceType"));
        assertEquals("2", details.get("readers"));
    }

    @Test
    void noReadyOrPrefixedPathsYieldsNoCandidates() throws IOException {
        String body = "{\"items\":[" + pathItem("stream/vision-own-output", true, "rtspSession", 0) + "]}";
        server.createContext("/v3/paths/list", exchange -> respond(exchange, 200, body));
        server.start();

        assertTrue(scanner().scan(Duration.ofSeconds(5)).isEmpty());
    }

    // -- failure handling: same "empty list, never throw" contract as every other scan ----------

    @Test
    void apiUnreachableReturnsEmptyListRatherThanThrowing() throws IOException {
        int unusedPort;
        try (ServerSocket probe = new ServerSocket(0)) {
            unusedPort = probe.getLocalPort();
        }
        MediamtxPathScanner scanner = new MediamtxPathScanner(
                new MediamtxScannerSettings(URI.create("http://127.0.0.1:" + unusedPort), RTSP_BASE, PREFIX));

        List<DiscoveredDevice> found = scanner.scan(Duration.ofSeconds(2));

        assertTrue(found.isEmpty());
    }

    @Test
    void nonTwoHundredResponseReturnsEmptyListRatherThanThrowing() throws IOException {
        server.createContext("/v3/paths/list", exchange -> respond(exchange, 500, "{\"error\":\"boom\"}"));
        server.start();

        assertTrue(scanner().scan(Duration.ofSeconds(5)).isEmpty());
    }

    @Test
    void malformedJsonReturnsEmptyListRatherThanThrowing() throws IOException {
        server.createContext("/v3/paths/list", exchange -> respond(exchange, 200, "not json at all, just noise"));
        server.start();

        assertTrue(scanner().scan(Duration.ofSeconds(5)).isEmpty());
    }

    /**
     * Proves the timeout is a real request timeout, not just a "skip if already exhausted" check --
     * mirrors {@code OnvifDeviceClientTest.probeStreamReturnsUnavailableWhenTheDeviceIsSlowerThanItsBudget}:
     * the handler sleeps 2s but the scanner is given a 200ms budget, so the call must abort near
     * 200ms rather than waiting out the handler.
     */
    @Test
    void timeoutIsRespectedEvenWhenTheServerNeverResponds() throws IOException {
        server.createContext("/v3/paths/list", exchange -> {
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            try {
                respond(exchange, 200, "{\"items\":[]}");
            } catch (IOException ignored) {
                // connection likely already abandoned by the client-side timeout
            }
        });
        server.start();

        long startNanos = System.nanoTime();
        List<DiscoveredDevice> found = scanner().scan(Duration.ofMillis(200));
        long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000L;

        assertTrue(found.isEmpty());
        assertTrue(elapsedMillis < 1500, "expected the call to abort near its 200ms budget, took " + elapsedMillis + "ms");
    }

    @Test
    void zeroOrNegativeTimeoutReturnsEmptyListImmediately() {
        MediamtxPathScanner scanner = scanner();

        assertTrue(scanner.scan(Duration.ZERO).isEmpty());
        assertTrue(scanner.scan(Duration.ofMillis(-1)).isEmpty());
    }

    @Test
    void methodIsMediamtx() {
        assertEquals("mediamtx", scanner().method());
    }

    private static String pathItem(String name, boolean ready, String sourceType, int readerCount) {
        StringBuilder readers = new StringBuilder("[");
        for (int i = 0; i < readerCount; i++) {
            if (i > 0) {
                readers.append(',');
            }
            readers.append("{\"type\":\"rtspSession\",\"id\":\"r").append(i).append("\"}");
        }
        readers.append(']');
        return "{\"name\":\"" + name + "\",\"confName\":\"all_others\",\"ready\":" + ready
                + ",\"source\":{\"type\":\"" + sourceType + "\",\"id\":\"x\"},\"tracks\":[],\"readers\":"
                + readers + "}";
    }
}
