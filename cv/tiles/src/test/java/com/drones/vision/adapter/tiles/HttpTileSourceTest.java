package com.drones.vision.adapter.tiles;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real loopback HTTP tests for {@link HttpTileSource} — a real {@link HttpServer} (JDK built-in,
 * no extra dependency) stands in for the tile provider, per this repo's "prefer real loopback over
 * mocks" testing convention. No docker/external prerequisite; nothing here is gated.
 */
class HttpTileSourceTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private String startServer(com.sun.net.httpserver.HttpHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", handler);
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private HttpTileSource newSource(String baseUrl, TileSourceSettings overrides) {
        HttpClient client = HttpClient.newBuilder().connectTimeout(overrides.timeout()).build();
        return new HttpTileSource(client, withUrlTemplate(overrides, baseUrl + "/{z}/{x}/{y}.jpg"));
    }

    private static TileSourceSettings withUrlTemplate(TileSourceSettings base, String urlTemplate) {
        return new TileSourceSettings(urlTemplate, base.zoom(), base.maxTiles(), base.concurrency(),
                base.requestsPerSecond(), base.timeout(), base.userAgent(), base.maxRetries());
    }

    @Test
    void supportsIsAlwaysTrue() {
        assertTrue(new HttpTileSource(TileSourceSettings.defaults()).supports());
    }

    @Test
    void fetchReturnsBodyBytesOnHttp200() throws Exception {
        byte[] jpegBytes = {1, 2, 3, 4, 5};
        String baseUrl = startServer(exchange -> respond(exchange, 200, jpegBytes));

        HttpTileSource source = newSource(baseUrl, TileSourceSettings.defaults());
        byte[] result = source.fetch(17, 5, 9);

        assertArrayEquals(jpegBytes, result);
    }

    @Test
    void fetchThrowsOnHttp404WithoutRetrying() throws Exception {
        AtomicInteger callCount = new AtomicInteger();
        String baseUrl = startServer(exchange -> {
            callCount.incrementAndGet();
            respond(exchange, 404, new byte[0]);
        });

        HttpTileSource source = newSource(baseUrl, TileSourceSettings.defaults());
        assertThrows(IllegalStateException.class, () -> source.fetch(17, 5, 9));
        assertEquals(1, callCount.get(), "a 404 is honest absence, never retried");
    }

    @Test
    void fetchOptionalReturnsEmptyOnHttp404WithoutRetrying() throws Exception {
        AtomicInteger callCount = new AtomicInteger();
        String baseUrl = startServer(exchange -> {
            callCount.incrementAndGet();
            respond(exchange, 404, new byte[0]);
        });

        HttpTileSource source = newSource(baseUrl, TileSourceSettings.defaults());
        Optional<byte[]> result = source.fetchOptional(17, 5, 9);

        assertTrue(result.isEmpty(), "fetchOptional -- the WaybackTileSource seam -- reports a 404 as empty");
        assertEquals(1, callCount.get());
    }

    @Test
    void fetchRequestsTheExactUrlTemplateSubstitution() throws Exception {
        CopyOnWriteArrayList<String> requestedPaths = new CopyOnWriteArrayList<>();
        String baseUrl = startServer(exchange -> {
            requestedPaths.add(exchange.getRequestURI().getPath());
            respond(exchange, 200, new byte[]{9});
        });

        HttpTileSource source = newSource(baseUrl, TileSourceSettings.defaults());
        source.fetch(12, 34, 56);

        assertEquals(1, requestedPaths.size());
        assertEquals("/12/34/56.jpg", requestedPaths.get(0));
    }

    @Test
    void fetchSendsTheConfiguredUserAgent() throws Exception {
        CopyOnWriteArrayList<String> userAgents = new CopyOnWriteArrayList<>();
        String baseUrl = startServer(exchange -> {
            userAgents.add(exchange.getRequestHeaders().getFirst("User-Agent"));
            respond(exchange, 200, new byte[]{1});
        });

        TileSourceSettings settings = TileSourceSettings.defaults();
        HttpTileSource source = newSource(baseUrl, settings);
        source.fetch(1, 1, 1);

        assertEquals(1, userAgents.size());
        assertEquals(settings.userAgent(), userAgents.get(0));
    }

    @Test
    void fetchRetriesOn429ThenSucceeds() throws Exception {
        AtomicInteger callCount = new AtomicInteger();
        byte[] tileBytes = {7, 7, 7};
        String baseUrl = startServer(exchange -> {
            int attempt = callCount.incrementAndGet();
            if (attempt < 3) {
                respond(exchange, 429, new byte[0]);
            } else {
                respond(exchange, 200, tileBytes);
            }
        });

        HttpTileSource source = newSource(baseUrl, fastRetrySettings());
        byte[] result = source.fetch(17, 1, 1);

        assertArrayEquals(tileBytes, result);
        assertEquals(3, callCount.get());
    }

    @Test
    void fetchRetriesOn503ThenSucceeds() throws Exception {
        AtomicInteger callCount = new AtomicInteger();
        byte[] tileBytes = {8, 8};
        String baseUrl = startServer(exchange -> {
            int attempt = callCount.incrementAndGet();
            if (attempt < 2) {
                respond(exchange, 503, new byte[0]);
            } else {
                respond(exchange, 200, tileBytes);
            }
        });

        HttpTileSource source = newSource(baseUrl, fastRetrySettings());
        byte[] result = source.fetch(17, 1, 1);

        assertArrayEquals(tileBytes, result);
    }

    @Test
    void fetchThrowsAfterExhaustingRetriesOnPersistent429() throws Exception {
        AtomicInteger callCount = new AtomicInteger();
        String baseUrl = startServer(exchange -> {
            callCount.incrementAndGet();
            respond(exchange, 429, new byte[0]);
        });

        HttpTileSource source = newSource(baseUrl, fastRetrySettings());
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> source.fetch(17, 1, 1));
        assertTrue(ex.getMessage().contains("17/1/1"));
        assertEquals(fastRetrySettings().maxRetries(), callCount.get());
    }

    @Test
    void fetchThrowsImmediatelyOnHttp403WithoutRetrying() throws Exception {
        AtomicInteger callCount = new AtomicInteger();
        String baseUrl = startServer(exchange -> {
            callCount.incrementAndGet();
            respond(exchange, 403, new byte[0]);
        });

        HttpTileSource source = newSource(baseUrl, fastRetrySettings());
        assertThrows(IllegalStateException.class, () -> source.fetch(1, 1, 1));
        assertEquals(1, callCount.get(), "a non-retryable status must not be retried");
    }

    @Test
    void fetchThrowsUncheckedIOExceptionWhenTheServerIsUnreachable() {
        // No server started at all; the port is one nothing is listening on.
        TileSourceSettings settings = withUrlTemplate(fastRetrySettings(), "http://127.0.0.1:1/{z}/{x}/{y}");
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(500)).build();
        HttpTileSource source = new HttpTileSource(client, settings);

        assertThrows(UncheckedIOException.class, () -> source.fetch(1, 1, 1));
    }

    @Test
    void fetchAtMostConcurrencyRequestsInFlightAtOnce() throws Exception {
        int concurrency = 2;
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger maxObservedInFlight = new AtomicInteger();
        String baseUrl = startServer(exchange -> {
            int current = inFlight.incrementAndGet();
            maxObservedInFlight.updateAndGet(prev -> Math.max(prev, current));
            try {
                Thread.sleep(150);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            inFlight.decrementAndGet();
            respond(exchange, 200, new byte[]{1});
        });

        TileSourceSettings settings = new TileSourceSettings(baseUrl + "/{z}/{x}/{y}", 17, 100, concurrency,
                1000.0, Duration.ofSeconds(5), "vision-geo-test/1.0", 3);
        HttpClient client = HttpClient.newBuilder().connectTimeout(settings.timeout()).build();
        HttpTileSource source = new HttpTileSource(client, settings);

        Thread[] threads = new Thread[6];
        for (int i = 0; i < threads.length; i++) {
            int idx = i;
            threads[i] = new Thread(() -> source.fetch(17, idx, idx));
            threads[i].start();
        }
        for (Thread t : threads) {
            t.join();
        }

        assertTrue(maxObservedInFlight.get() <= concurrency,
                "observed " + maxObservedInFlight.get() + " concurrent requests, expected <= " + concurrency);
    }

    @Test
    void constructorRejectsNullArguments() {
        assertThrows(NullPointerException.class, () -> new HttpTileSource((TileSourceSettings) null));
        assertThrows(NullPointerException.class,
                () -> new HttpTileSource((HttpClient) null, TileSourceSettings.defaults()));
        assertThrows(NullPointerException.class,
                () -> new HttpTileSource(HttpClient.newHttpClient(), null));
    }

    private static TileSourceSettings fastRetrySettings() {
        // Small maxRetries and the smallest legal-ish requestsPerSecond ceiling so the test doesn't
        // wait out this class's real 1s/2s/... exponential backoff; concurrency/timeout otherwise
        // match production defaults.
        return new TileSourceSettings(TileSourceSettings.defaults().urlTemplate(), 17, 100, 4, 1_000.0,
                Duration.ofSeconds(2), "vision-geo-test/1.0", 3);
    }

    private static void respond(HttpExchange exchange, int status, byte[] body) throws IOException {
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }
}
