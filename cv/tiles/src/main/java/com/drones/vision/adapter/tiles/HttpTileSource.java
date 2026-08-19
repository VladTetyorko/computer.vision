package com.drones.vision.adapter.tiles;

import com.drones.vision.perception.domain.port.ReferenceTileSourcePort;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * {@link ReferenceTileSourcePort} over the JDK {@link HttpClient} — no third-party HTTP dependency
 * (harvested from {@code feat/visual-geo}'s {@code adapter-tiles}, docs/plans/active/
 * VISUAL-GEO-V2-PLAN.md §1.3/D3). Fetches one raster tile per call against a configurable
 * slippy-map URL template (default: the same Esri World Imagery endpoint {@code vision-web}'s
 * satellite basemap already uses).
 *
 * <h2>Listen-vs-dial semantics</h2>
 * Purely a dialing (outbound HTTP client) adapter — no listen/bind side at all.
 *
 * <h2>Adapted from the harvested port shape</h2>
 * The parked branch's {@code ReferenceTileSourcePort#fetch} returned {@code Optional<byte[]>},
 * {@code Optional.empty()} meaning an honest 404 (out of the source's covered area). The
 * current port ({@link ReferenceTileSourcePort#fetch(int, int, int)}) instead returns a bare {@code
 * byte[]} and folds "tile not found" into its documented {@code RuntimeException} contract — {@code
 * DefaultReferenceRegionService} builds a {@code Tile} straight from this method's return value with
 * no {@code Optional} to unwrap, so a region ingest that hits an uncovered tile now fails loudly
 * rather than silently shipping a thinner-than-requested pack. This class's 404 handling was
 * adjusted accordingly (throws {@link IllegalStateException} instead of returning empty); every
 * other retry/backoff/politeness behavior is unchanged from the harvest.
 *
 * <h2>Retry/backoff</h2>
 * <ul>
 *   <li><b>HTTP 200</b>: returns the response body.</li>
 *   <li><b>HTTP 404</b>: {@link #fetch(int, int, int)} throws {@link IllegalStateException}
 *   immediately, no retry — honest absence (out of the source's covered area) is still not worth
 *   retrying, but it is no longer a value this port can return. {@link #fetchOptional(int, int,
 *   int)} — the package-private seam {@link WaybackTileSource} uses internally to tell "this
 *   release doesn't cover the tile" apart from a genuine failure — still reports it as {@link
 *   Optional#empty()}.</li>
 *   <li><b>HTTP 429 or 5xx</b>: retried up to {@link TileSourceSettings#maxRetries()} times with
 *   exponential backoff (capped at {@value #MAX_BACKOFF_MILLIS}ms) plus jitter; if every attempt is
 *   exhausted, throws {@link IllegalStateException}.</li>
 *   <li><b>Any other HTTP status</b> (e.g. 401/403 — a misconfigured template or revoked key):
 *   throws {@link IllegalStateException} immediately, no retry.</li>
 *   <li><b>Network-level {@link IOException}</b> (connect failure, timeout): retried the same way as
 *   429/5xx; if exhausted, throws {@link UncheckedIOException} wrapping the last failure.</li>
 *   <li><b>Interrupted while waiting on the rate limiter or backing off</b>: the thread's interrupt
 *   flag is restored and {@link IllegalStateException} is thrown.</li>
 * </ul>
 *
 * <h2>Politeness beyond the bare port contract</h2>
 * This class self-governs internally (a shared {@link TileRateLimiter} pacing {@link
 * TileSourceSettings#requestsPerSecond()}, a {@link Semaphore} bounding {@link
 * TileSourceSettings#concurrency()} requests in flight at once) — the port's own contract sets a
 * floor (no implementation is <em>required</em> to rate-limit), not a ceiling, and a real
 * implementation talking to a third-party tile provider (rate-limited, terms-of-service-bound)
 * should not silently rely on every future caller getting its own pacing right. Both limits apply
 * per-{@code HttpTileSource} <em>instance</em>; a caller may still call {@link #fetch} from
 * multiple threads to parallelize a large pack's fetch, and this class throttles the actual
 * outbound requests to the configured budget either way.
 */
public final class HttpTileSource implements ReferenceTileSourcePort {

    private static final System.Logger LOG = System.getLogger(HttpTileSource.class.getName());

    private static final int HTTP_OK = 200;
    private static final int HTTP_NOT_FOUND = 404;
    private static final int HTTP_TOO_MANY_REQUESTS = 429;
    private static final int HTTP_SERVER_ERROR_FLOOR = 500;
    private static final int HTTP_SERVER_ERROR_CEILING = 600;
    private static final long INITIAL_BACKOFF_MILLIS = 1_000;
    private static final long MAX_BACKOFF_MILLIS = 30_000;

    private final HttpClient httpClient;
    private final TileSourceSettings settings;
    private final Semaphore concurrencyGate;
    private final TileRateLimiter rateLimiter;

    /**
     * Production convenience constructor: builds its own {@link HttpClient} tuned from {@code
     * settings}.
     *
     * @param settings this source's tunables; see {@link TileSourceSettings}
     */
    public HttpTileSource(TileSourceSettings settings) {
        this(HttpClient.newBuilder()
                .connectTimeout(Objects.requireNonNull(settings, "settings must not be null").timeout())
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build(), settings);
    }

    /** Test seam: an explicit {@link HttpClient} (e.g. one that talks to a loopback test server). */
    HttpTileSource(HttpClient httpClient, TileSourceSettings settings) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient must not be null");
        this.settings = Objects.requireNonNull(settings, "settings must not be null");
        this.concurrencyGate = new Semaphore(settings.concurrency());
        this.rateLimiter = new TileRateLimiter(settings.requestsPerSecond());
    }

    /** Always {@code true}: an instance cannot exist without a configured {@link #settings} template. */
    @Override
    public boolean supports() {
        return true;
    }

    @Override
    public byte[] fetch(int zoom, int x, int y) {
        return fetchOptional(zoom, x, y)
                .orElseThrow(() -> new IllegalStateException(
                        "Tile source has no coverage for tile " + tileId(zoom, x, y) + " (HTTP 404)"));
    }

    /**
     * Same fetch as {@link #fetch(int, int, int)}, except a 404 is reported as {@link
     * Optional#empty()} rather than an exception — used internally by {@link WaybackTileSource} to
     * distinguish "this release doesn't cover the tile" (skip it, try the next release) from a
     * genuine failure (propagate). Every other outcome (success, retry exhaustion, a non-retryable
     * status, a network failure) behaves identically to {@link #fetch(int, int, int)}.
     */
    Optional<byte[]> fetchOptional(int zoom, int x, int y) {
        String url = settings.urlTemplate()
                .replace("{z}", String.valueOf(zoom))
                .replace("{x}", String.valueOf(x))
                .replace("{y}", String.valueOf(y));
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(settings.timeout())
                .header("User-Agent", settings.userAgent())
                .GET()
                .build();

        try {
            concurrencyGate.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting to fetch tile " + tileId(zoom, x, y), e);
        }
        try {
            return fetchWithRetry(request, zoom, x, y);
        } finally {
            concurrencyGate.release();
        }
    }

    private Optional<byte[]> fetchWithRetry(HttpRequest request, int zoom, int x, int y) {
        long backoffMillis = INITIAL_BACKOFF_MILLIS;
        IOException lastIoFailure = null;

        for (int attempt = 1; attempt <= settings.maxRetries(); attempt++) {
            try {
                rateLimiter.acquire();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(
                        "Interrupted while rate-limiting fetch of tile " + tileId(zoom, x, y), e);
            }

            try {
                HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
                int status = response.statusCode();
                if (status == HTTP_OK) {
                    return Optional.of(response.body());
                }
                if (status == HTTP_NOT_FOUND) {
                    return Optional.empty();
                }
                if (isRetryable(status)) {
                    LOG.log(System.Logger.Level.WARNING, "tile " + tileId(zoom, x, y) + " HTTP " + status
                            + " (attempt " + attempt + "/" + settings.maxRetries() + "), backing off "
                            + backoffMillis + "ms");
                    backoffMillis = sleepBackoff(zoom, x, y, backoffMillis);
                    continue;
                }
                throw new IllegalStateException(
                        "Tile source refused tile " + tileId(zoom, x, y) + " with HTTP " + status);
            } catch (IOException e) {
                lastIoFailure = e;
                LOG.log(System.Logger.Level.WARNING, "tile " + tileId(zoom, x, y) + " fetch failed (attempt "
                        + attempt + "/" + settings.maxRetries() + "): " + e);
                backoffMillis = sleepBackoff(zoom, x, y, backoffMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while fetching tile " + tileId(zoom, x, y), e);
            }
        }

        LOG.log(System.Logger.Level.WARNING,
                () -> "tile " + tileId(zoom, x, y) + " exhausted " + settings.maxRetries() + " retries, giving up");
        if (lastIoFailure != null) {
            throw new UncheckedIOException(
                    "Failed to fetch tile " + tileId(zoom, x, y) + " after " + settings.maxRetries() + " attempts",
                    lastIoFailure);
        }
        throw new IllegalStateException(
                "Failed to fetch tile " + tileId(zoom, x, y) + " after " + settings.maxRetries() + " attempts");
    }

    private long sleepBackoff(int zoom, int x, int y, long backoffMillis) {
        try {
            TimeUnit.MILLISECONDS.sleep(backoffMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while backing off fetch of tile " + tileId(zoom, x, y), e);
        }
        return Math.min(backoffMillis * 2, MAX_BACKOFF_MILLIS);
    }

    private static boolean isRetryable(int status) {
        return status == HTTP_TOO_MANY_REQUESTS
                || (status >= HTTP_SERVER_ERROR_FLOOR && status < HTTP_SERVER_ERROR_CEILING);
    }

    private static String tileId(int zoom, int x, int y) {
        return zoom + "/" + x + "/" + y;
    }
}
