package com.drones.vision.adapter.tiles;

import java.time.Duration;
import java.util.Objects;

/**
 * Plain, framework-free settings for {@link HttpTileSource} (and, per-release, {@link
 * WaybackTileSource}) — the single source for every {@code vision.geo.visual.tiles.*} tunable
 * (docs/plans/done/VISUAL-GEO-V2-PLAN.md §3.6) this module needs. No Spring annotations here,
 * mirroring {@code cv/grpc}'s {@code GrpcCvSettings}: {@code vision-app} owns a {@code
 * VisionGeoProperties} record bound to {@code application.yaml} and maps it to one of these before
 * handing it to {@link HttpTileSource}'s constructor (H5) — this class must never be constructed
 * from a {@code @ConfigurationProperties} type directly.
 *
 * @param urlTemplate       the tile source's URL template; must contain the literal placeholders
 *                          {@code {z}}, {@code {x}}, {@code {y}} (the default Esri endpoint is
 *                          {@code {z}/{y}/{x}} order, <b>not</b> {@code {z}/{x}/{y}} — a trap this
 *                          class's own tests pin); must not be blank
 * @param zoom              the configured default reference-tile zoom level; must be within [0,22]
 * @param maxTiles          the configured hard cap on tiles in one region's reference pack; must be
 *                          positive
 * @param concurrency       maximum number of {@link HttpTileSource#fetch} calls allowed in flight at
 *                          once across every caller thread, enforced by an internal semaphore; must
 *                          be positive
 * @param requestsPerSecond maximum outbound request rate across every caller thread, enforced by an
 *                          internal rate limiter; must be positive and finite
 * @param timeout           per-attempt HTTP connect+response timeout; must be positive
 * @param userAgent         the {@code User-Agent} header sent with every request; must not be blank
 * @param maxRetries        maximum attempts (the first try plus retries) before {@link
 *                          HttpTileSource#fetch} gives up on a {@code 429}/{@code 5xx}/network
 *                          failure and throws; must be positive
 */
public record TileSourceSettings(
        String urlTemplate,
        int zoom,
        int maxTiles,
        int concurrency,
        double requestsPerSecond,
        Duration timeout,
        String userAgent,
        int maxRetries) {

    /**
     * The same Esri World Imagery endpoint {@code vision-web}'s satellite basemap already uses.
     * Note the {@code {z}/{y}/{x}} placeholder order — <b>not</b> {@code {z}/{x}/{y}}.
     */
    static final String DEFAULT_URL_TEMPLATE =
            "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}";

    /** Default {@link #zoom()} — §4.6's frozen default (z16 measured a 0.75-0.96 false-fix rate). */
    static final int DEFAULT_ZOOM = 17;

    /** Default {@link #maxTiles()} — descriptor-budget arithmetic (VISUAL-GEO-V2-PLAN.md §3.6). */
    static final int DEFAULT_MAX_TILES = 50_000;

    /** Default {@link #concurrency()}. */
    static final int DEFAULT_CONCURRENCY = 4;

    /** Default {@link #requestsPerSecond()}. */
    static final double DEFAULT_REQUESTS_PER_SECOND = 20.0;

    /** Default {@link #timeout()}. */
    static final long DEFAULT_TIMEOUT_SECONDS = 10;

    /** Default {@link #userAgent()} — VISUAL-GEO-V2-PLAN.md §3.6. */
    static final String DEFAULT_USER_AGENT = "vision-geo/0.0.2";

    /**
     * Default {@link #maxRetries()} — not itself a named {@code vision.geo.visual.tiles.*} key, but
     * retry/backoff (an explicit deliverable of this class) needs a bound; mirrors the Wave 0
     * spike's own {@code DEFAULT_MAX_RETRIES=5}.
     */
    static final int DEFAULT_MAX_RETRIES = 5;

    private static final String ZOOM_PLACEHOLDER = "{z}";
    private static final String X_PLACEHOLDER = "{x}";
    private static final String Y_PLACEHOLDER = "{y}";
    private static final int MAX_ZOOM = 22;

    public TileSourceSettings {
        if (urlTemplate == null || urlTemplate.isBlank()) {
            throw new IllegalArgumentException("urlTemplate must not be blank");
        }
        if (!urlTemplate.contains(ZOOM_PLACEHOLDER) || !urlTemplate.contains(X_PLACEHOLDER)
                || !urlTemplate.contains(Y_PLACEHOLDER)) {
            throw new IllegalArgumentException(
                    "urlTemplate must contain {z}, {x} and {y} placeholders: " + urlTemplate);
        }
        if (zoom < 0 || zoom > MAX_ZOOM) {
            throw new IllegalArgumentException("zoom must be within [0," + MAX_ZOOM + "]: " + zoom);
        }
        if (maxTiles <= 0) {
            throw new IllegalArgumentException("maxTiles must be positive: " + maxTiles);
        }
        if (concurrency <= 0) {
            throw new IllegalArgumentException("concurrency must be positive: " + concurrency);
        }
        if (!Double.isFinite(requestsPerSecond) || requestsPerSecond <= 0) {
            throw new IllegalArgumentException("requestsPerSecond must be positive and finite: " + requestsPerSecond);
        }
        Objects.requireNonNull(timeout, "timeout must not be null");
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be positive: " + timeout);
        }
        if (userAgent == null || userAgent.isBlank()) {
            throw new IllegalArgumentException("userAgent must not be blank");
        }
        if (maxRetries <= 0) {
            throw new IllegalArgumentException("maxRetries must be positive: " + maxRetries);
        }
    }

    /** Every default matches VISUAL-GEO-V2-PLAN.md §3.6's shipped {@code tiles:} config block. */
    public static TileSourceSettings defaults() {
        return new TileSourceSettings(DEFAULT_URL_TEMPLATE, DEFAULT_ZOOM, DEFAULT_MAX_TILES, DEFAULT_CONCURRENCY,
                DEFAULT_REQUESTS_PER_SECOND, Duration.ofSeconds(DEFAULT_TIMEOUT_SECONDS), DEFAULT_USER_AGENT,
                DEFAULT_MAX_RETRIES);
    }
}
