package com.drones.vision.adapter.tiles;

import com.drones.vision.adapter.tiles.WaybackReleaseCatalog.WaybackRelease;
import com.drones.vision.adapter.tiles.WaybackTileSource.WaybackFetchResult;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real loopback HTTP tests for {@link WaybackTileSource} — one real {@link HttpServer} per
 * simulated Wayback release (JDK built-in, no extra dependency), same "prefer real loopback over
 * mocks" convention {@link HttpTileSourceTest} already follows in this module.
 */
class WaybackTileSourceTest {

    private static final int SIZE = 16;

    private final List<HttpServer> servers = new ArrayList<>();

    @AfterEach
    void tearDown() {
        for (HttpServer server : servers) {
            server.stop(0);
        }
    }

    @Test
    void supportsIsAlwaysTrue() {
        assertTrue(new WaybackTileSource(WaybackReleaseCatalog.defaults(),
                WaybackTileSource.recommendedPerReleaseSettings()).supports());
    }

    @Test
    void fetchPicksTheLeastOccludedCandidateAmongMultipleReleases() throws Exception {
        AtomicInteger whiteCalls = new AtomicInteger();
        AtomicInteger blackCalls = new AtomicInteger();
        AtomicInteger halfCalls = new AtomicInteger();
        String whiteUrl = startServer(exchange -> {
            whiteCalls.incrementAndGet();
            respond(exchange, 200, solidColorJpeg(Color.WHITE));
        });
        String blackUrl = startServer(exchange -> {
            blackCalls.incrementAndGet();
            respond(exchange, 200, solidColorJpeg(Color.BLACK));
        });
        // A solid color's occlusion score is a step function (every pixel shares one luma), so a
        // genuinely intermediate candidate needs a mixed image, not a "medium gray" solid fill.
        String halfUrl = startServer(exchange -> {
            halfCalls.incrementAndGet();
            respond(exchange, 200, halfBlackHalfWhiteJpeg());
        });

        WaybackReleaseCatalog catalog = catalogOf(
                release("2014-01-01", "black-rel", blackUrl),
                release("2020-01-01", "half-rel", halfUrl),
                release("2025-01-01", "white-rel", whiteUrl));
        WaybackTileSource source = newSource(catalog, fastSettings());

        Optional<WaybackFetchResult> result = source.fetchWithDiagnostics(17, 5, 9);

        assertTrue(result.isPresent());
        assertEquals("white-rel", result.get().releaseNumber(), "the all-white candidate is the least occluded");
        assertEquals("2025-01-01", result.get().releaseDate());
        assertTrue(result.get().occlusionScore() < 0.05, "expected a near-zero occlusion score for the winner");

        assertEquals(1, whiteCalls.get());
        assertEquals(1, blackCalls.get());
        assertEquals(1, halfCalls.get());
    }

    @Test
    void fetchReturnsThePlainBytesOfWhicheverCandidateFetchWithDiagnosticsWouldPick() throws Exception {
        byte[] tileBytes = solidColorJpeg(Color.WHITE);
        String url = startServer(exchange -> respond(exchange, 200, tileBytes));
        WaybackReleaseCatalog catalog = catalogOf(release("2020-01-01", "only-rel", url));
        WaybackTileSource source = newSource(catalog, fastSettings());

        byte[] result = source.fetch(17, 2, 2);

        assertArrayEquals(tileBytes, result);
    }

    @Test
    void fetchSkipsReleasesThat404WithoutTreatingThemAsFailure() throws Exception {
        String missingUrl = startServer(exchange -> respond(exchange, 404, new byte[0]));
        byte[] tileBytes = solidColorJpeg(Color.WHITE);
        String presentUrl = startServer(exchange -> respond(exchange, 200, tileBytes));

        WaybackReleaseCatalog catalog = catalogOf(
                release("2014-01-01", "missing-rel", missingUrl),
                release("2020-01-01", "present-rel", presentUrl));
        WaybackTileSource source = newSource(catalog, fastSettings());

        Optional<WaybackFetchResult> result = source.fetchWithDiagnostics(17, 1, 1);

        assertTrue(result.isPresent());
        assertEquals("present-rel", result.get().releaseNumber());
        assertArrayEquals(tileBytes, result.get().bytes());
    }

    @Test
    void fetchWithDiagnosticsReturnsEmptyWhenEveryReleaseIs404() throws Exception {
        String urlA = startServer(exchange -> respond(exchange, 404, new byte[0]));
        String urlB = startServer(exchange -> respond(exchange, 404, new byte[0]));

        WaybackReleaseCatalog catalog = catalogOf(
                release("2014-01-01", "a", urlA),
                release("2020-01-01", "b", urlB));
        WaybackTileSource source = newSource(catalog, fastSettings());

        assertTrue(source.fetchWithDiagnostics(17, 1, 1).isEmpty());
    }

    @Test
    void fetchThrowsWhenEveryReleaseIs404() throws Exception {
        // Adapted from the harvest: the current ReferenceTileSourcePort#fetch has no Optional to
        // return empty into (VISUAL-GEO-V2-PLAN.md §1.3's "extract, one adapt") -- absence is now a
        // thrown IllegalStateException, mirroring HttpTileSource#fetch's own 404 contract.
        String urlA = startServer(exchange -> respond(exchange, 404, new byte[0]));
        String urlB = startServer(exchange -> respond(exchange, 404, new byte[0]));

        WaybackReleaseCatalog catalog = catalogOf(
                release("2014-01-01", "a", urlA),
                release("2020-01-01", "b", urlB));
        WaybackTileSource source = newSource(catalog, fastSettings());

        assertThrows(IllegalStateException.class, () -> source.fetch(17, 1, 1));
    }

    @Test
    void fetchPropagatesTheFailureWhenEveryReleaseGenuinelyErrors() throws Exception {
        String urlA = startServer(exchange -> respond(exchange, 503, new byte[0]));
        String urlB = startServer(exchange -> respond(exchange, 503, new byte[0]));

        WaybackReleaseCatalog catalog = catalogOf(
                release("2014-01-01", "a", urlA),
                release("2020-01-01", "b", urlB));
        WaybackTileSource source = newSource(catalog, fastSettings());

        assertThrows(IllegalStateException.class, () -> source.fetch(17, 1, 1));
    }

    @Test
    void fetchStillSucceedsWhenOneReleaseSucceedsAndAnotherGenuinelyErrors() throws Exception {
        byte[] tileBytes = solidColorJpeg(Color.WHITE);
        String okUrl = startServer(exchange -> respond(exchange, 200, tileBytes));
        String failingUrl = startServer(exchange -> respond(exchange, 503, new byte[0]));

        WaybackReleaseCatalog catalog = catalogOf(
                release("2014-01-01", "failing-rel", failingUrl),
                release("2020-01-01", "ok-rel", okUrl));
        WaybackTileSource source = newSource(catalog, fastSettings());

        byte[] result = source.fetch(17, 1, 1);

        assertArrayEquals(tileBytes, result, "a genuine failure on one release must not sink a success from another");
    }

    @Test
    void fetchQueriesExactlyTheConfiguredReleasesNotMore() throws Exception {
        AtomicInteger callsA = new AtomicInteger();
        AtomicInteger callsB = new AtomicInteger();
        AtomicInteger callsC = new AtomicInteger();
        String urlA = startServer(exchange -> {
            callsA.incrementAndGet();
            respond(exchange, 200, solidColorJpeg(Color.WHITE));
        });
        String urlB = startServer(exchange -> {
            callsB.incrementAndGet();
            respond(exchange, 200, solidColorJpeg(Color.WHITE));
        });
        String urlC = startServer(exchange -> {
            callsC.incrementAndGet();
            respond(exchange, 200, solidColorJpeg(Color.WHITE));
        });

        // Only 3 configured, well below the 10-release production default -- confirms this class
        // queries exactly what the catalog configures, never the default catalog underneath.
        WaybackReleaseCatalog catalog = catalogOf(
                release("2014-01-01", "a", urlA),
                release("2020-01-01", "b", urlB),
                release("2022-01-01", "c", urlC));
        WaybackTileSource source = newSource(catalog, fastSettings());

        source.fetch(17, 1, 1);

        assertEquals(1, callsA.get());
        assertEquals(1, callsB.get());
        assertEquals(1, callsC.get());
    }

    @Test
    void constructorRejectsNullArguments() {
        WaybackReleaseCatalog catalog = WaybackReleaseCatalog.defaults();
        TileSourceSettings settings = TileSourceSettings.defaults();

        assertThrows(NullPointerException.class, () -> new WaybackTileSource(null, settings));
        assertThrows(NullPointerException.class, () -> new WaybackTileSource(catalog, null));
        assertThrows(NullPointerException.class,
                () -> new WaybackTileSource((HttpClient) null, catalog, settings));
        assertThrows(NullPointerException.class,
                () -> new WaybackTileSource(HttpClient.newHttpClient(), null, settings));
        assertThrows(NullPointerException.class,
                () -> new WaybackTileSource(HttpClient.newHttpClient(), catalog, null));
    }

    @Test
    void recommendedPerReleaseSettingsIsTunedDownFromHttpTileSourceDefaults() {
        TileSourceSettings recommended = WaybackTileSource.recommendedPerReleaseSettings();
        TileSourceSettings singleDateDefaults = TileSourceSettings.defaults();

        assertTrue(recommended.concurrency() < singleDateDefaults.concurrency(),
                "wayback concurrency should be tuned down since it multiplies request volume by release count");
        assertTrue(recommended.requestsPerSecond() < singleDateDefaults.requestsPerSecond(),
                "wayback request rate should be tuned down for the same reason");
    }

    private WaybackTileSource newSource(WaybackReleaseCatalog catalog, TileSourceSettings settings) {
        HttpClient client = HttpClient.newBuilder().connectTimeout(settings.timeout()).build();
        return new WaybackTileSource(client, catalog, settings);
    }

    private static WaybackReleaseCatalog catalogOf(WaybackRelease... releases) {
        return new WaybackReleaseCatalog(List.of(releases));
    }

    private static WaybackRelease release(String date, String releaseNumber, String baseUrl) {
        return new WaybackRelease(date, releaseNumber, baseUrl + "/{level}/{row}/{col}");
    }

    /** Small maxRetries + a high requests-per-second ceiling so 503 tests don't wait out real backoff. */
    private static TileSourceSettings fastSettings() {
        return new TileSourceSettings(TileSourceSettings.defaults().urlTemplate(), 17, 100, 4, 1_000.0,
                Duration.ofSeconds(2), "vision-geo-wayback-test/1.0", 2);
    }

    private String startServer(HttpHandlerThatMayThrow handler) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try {
                handler.handle(exchange);
            } catch (Exception e) {
                respond(exchange, 500, new byte[0]);
            }
        });
        server.start();
        servers.add(server);
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @FunctionalInterface
    private interface HttpHandlerThatMayThrow {
        void handle(HttpExchange exchange) throws Exception;
    }

    private static void respond(HttpExchange exchange, int status, byte[] body) throws IOException {
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private static byte[] solidColorJpeg(Color color) throws IOException {
        BufferedImage image = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setColor(color);
            g.fillRect(0, 0, SIZE, SIZE);
        } finally {
            g.dispose();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", out);
        return out.toByteArray();
    }

    private static byte[] halfBlackHalfWhiteJpeg() throws IOException {
        BufferedImage image = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setColor(Color.BLACK);
            g.fillRect(0, 0, SIZE, SIZE / 2);
            g.setColor(Color.WHITE);
            g.fillRect(0, SIZE / 2, SIZE, SIZE / 2);
        } finally {
            g.dispose();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", out);
        return out.toByteArray();
    }
}
