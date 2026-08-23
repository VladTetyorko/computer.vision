package com.drones.vision.adapter.tiles;

import com.drones.vision.perception.domain.port.ReferenceTileSourcePort;

import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * {@link ReferenceTileSourcePort} that fetches one tile from <em>every</em> configured Wayback
 * historical release in parallel, scores each successful candidate with {@link TileOcclusionScorer},
 * and returns the least-occluded one (harvested from {@code feat/visual-geo}'s {@code
 * adapter-tiles}, docs/plans/done/VISUAL-GEO-V2-PLAN.md §1.3/§3.6's {@code wayback-multi-date}
 * knob) — purely additive, zero changes to the existing single-date {@link HttpTileSource} or its
 * callers.
 *
 * <p>Internally wraps one {@link HttpTileSource} per {@link WaybackReleaseCatalog.WaybackRelease},
 * each built from the <b>same</b> {@link TileSourceSettings} (concurrency, rate limit, timeout,
 * retries, user agent) except {@code urlTemplate}, which is overridden per release from {@link
 * WaybackReleaseCatalog.WaybackRelease#zxyUrlTemplate()} — this reuses {@link HttpTileSource}'s
 * actual HTTP/retry/backoff code wholesale rather than duplicating it (the caller-supplied {@code
 * settings.urlTemplate()} itself is therefore ignored; only its other fields are used).
 *
 * <h2>Listen-vs-dial semantics</h2>
 * Same as {@link HttpTileSource}: purely a dialing (outbound HTTP client) adapter.
 *
 * <h2>Concurrency and politeness</h2>
 * A single {@link #fetch(int, int, int)} call queries <b>every</b> configured release at once, each
 * on its own virtual thread (cheap, daemon, no shared executor to size or shut down). This
 * multiplies outbound request volume by the release count relative to a single-date {@link
 * HttpTileSource}, so the shared {@code settings} passed to this class's constructor should be
 * tuned <b>down</b> from {@link TileSourceSettings#defaults()} (see {@link
 * #recommendedPerReleaseSettings()} for a starting point) — not left at single-date defaults. Each
 * release's own {@link HttpTileSource} still self-governs independently, so the <em>effective</em>
 * ceiling on simultaneous outbound requests to the Wayback host, across however many threads a
 * caller uses to assemble a whole region's pack, is {@code concurrency &times; releaseCount} —
 * configuring {@code concurrency=1} bounds it to exactly one in-flight request per release.
 *
 * <h2>Selection, and the port's throw-on-absence contract</h2>
 * <ul>
 *   <li>At least one release covers the tile: {@link #fetch} returns the <b>lowest-scoring</b>
 *   (least-occluded) candidate's bytes — regardless of whether other releases had no coverage or
 *   genuinely failed. A release missing this tile, or transiently failing, must not sink a result
 *   another release can supply.</li>
 *   <li>No release covers the tile, and every one reported honest absence (no configured release
 *   ever covered this tile): {@link #fetch} throws {@link IllegalStateException}, mirroring {@link
 *   HttpTileSource#fetch}'s own throw-on-404 contract (§ current {@link ReferenceTileSourcePort}
 *   has no {@code Optional} to return empty into — see {@link #fetchWithDiagnostics} for the
 *   richer, {@code Optional}-returning diagnostic form this method is built on).</li>
 *   <li>No release covers the tile, and at least one threw a genuine failure (rate-limit
 *   exhaustion, a non-retryable HTTP status, a network failure) rather than reporting a clean
 *   absence: propagates that failure (the first one encountered) exactly as {@link HttpTileSource}
 *   would have.</li>
 * </ul>
 *
 * <h2>Test seam</h2>
 * Like {@link HttpTileSource}, a package-private constructor accepts an explicit {@link HttpClient}
 * (e.g. one that talks to loopback test servers), shared across every release's internal {@link
 * HttpTileSource}.
 */
public final class WaybackTileSource implements ReferenceTileSourcePort {

    private static final System.Logger LOG = System.getLogger(WaybackTileSource.class.getName());

    /** Tuned-down concurrency for {@link #recommendedPerReleaseSettings()} — see that method. */
    private static final int RECOMMENDED_CONCURRENCY = 1;

    /** Tuned-down request rate for {@link #recommendedPerReleaseSettings()} — see that method. */
    private static final double RECOMMENDED_REQUESTS_PER_SECOND = 2.0;

    private final List<ReleaseSource> releaseSources;

    /**
     * Production convenience constructor: builds its own {@link HttpClient} tuned from {@code
     * settings}, shared by every release.
     *
     * @param catalog  the releases to query on every {@link #fetch}; see {@link
     *                 WaybackReleaseCatalog}
     * @param settings tunables shared by every release's internal {@link HttpTileSource} — {@code
     *                 urlTemplate} is ignored (each release supplies its own); see {@link
     *                 #recommendedPerReleaseSettings()} for a starting point tuned down from {@link
     *                 TileSourceSettings#defaults()}
     */
    public WaybackTileSource(WaybackReleaseCatalog catalog, TileSourceSettings settings) {
        this(HttpClient.newBuilder()
                .connectTimeout(Objects.requireNonNull(settings, "settings must not be null").timeout())
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build(), catalog, settings);
    }

    /** Test seam: an explicit {@link HttpClient} (e.g. one that talks to loopback test servers). */
    WaybackTileSource(HttpClient httpClient, WaybackReleaseCatalog catalog, TileSourceSettings settings) {
        Objects.requireNonNull(httpClient, "httpClient must not be null");
        Objects.requireNonNull(catalog, "catalog must not be null");
        Objects.requireNonNull(settings, "settings must not be null");

        List<ReleaseSource> sources = new ArrayList<>(catalog.releases().size());
        for (WaybackReleaseCatalog.WaybackRelease release : catalog.releases()) {
            TileSourceSettings releaseSettings = withUrlTemplate(settings, release.zxyUrlTemplate());
            sources.add(new ReleaseSource(release, new HttpTileSource(httpClient, releaseSettings)));
        }
        this.releaseSources = List.copyOf(sources);
    }

    /**
     * A starting point for the {@code settings} constructor parameter, tuned down from {@link
     * TileSourceSettings#defaults()}'s single-date concurrency/rate (concurrency=4, 20 req/s) to
     * {@value #RECOMMENDED_CONCURRENCY} concurrency and {@value #RECOMMENDED_REQUESTS_PER_SECOND}
     * req/s — a single {@link #fetch} call already multiplies request volume by the configured
     * release count (10 by default), so per-release politeness needs to be correspondingly gentler.
     * Not itself wired to a {@code vision.geo.visual.tiles.*} property; a caller is free to pass its
     * own {@link TileSourceSettings} instead.
     */
    public static TileSourceSettings recommendedPerReleaseSettings() {
        TileSourceSettings base = TileSourceSettings.defaults();
        return new TileSourceSettings(base.urlTemplate(), base.zoom(), base.maxTiles(), RECOMMENDED_CONCURRENCY,
                RECOMMENDED_REQUESTS_PER_SECOND, base.timeout(), base.userAgent(), base.maxRetries());
    }

    /** Always {@code true}: an instance cannot exist without a configured, non-empty {@link WaybackReleaseCatalog}. */
    @Override
    public boolean supports() {
        return true;
    }

    @Override
    public byte[] fetch(int zoom, int x, int y) {
        return fetchWithDiagnostics(zoom, x, y)
                .map(WaybackFetchResult::bytes)
                .orElseThrow(() -> new IllegalStateException(
                        "No configured Wayback release covers tile " + tileId(zoom, x, y)));
    }

    /**
     * Same selection as {@link #fetch(int, int, int)}, but also reports which release won and its
     * occlusion score, instead of only the winning bytes — for operator-facing diagnostics and
     * acceptance evidence, without duplicating this class's own selection logic in a caller.
     *
     * @param zoom the tile's zoom level
     * @param x    the tile's x coordinate at {@code zoom}
     * @param y    the tile's y coordinate at {@code zoom}
     * @return the winning candidate, or {@link Optional#empty()} when every configured release
     *         honestly reported no coverage for this tile
     */
    public Optional<WaybackFetchResult> fetchWithDiagnostics(int zoom, int x, int y) {
        List<CompletableFuture<Optional<ScoredCandidate>>> pending = new ArrayList<>(releaseSources.size());
        for (ReleaseSource source : releaseSources) {
            pending.add(fetchAndScoreAsync(source, zoom, x, y));
        }

        ScoredCandidate best = null;
        RuntimeException firstFailure = null;
        for (int i = 0; i < pending.size(); i++) {
            ReleaseSource source = releaseSources.get(i);
            try {
                Optional<ScoredCandidate> candidate = pending.get(i).join();
                if (candidate.isPresent() && (best == null || candidate.get().score() < best.score())) {
                    best = candidate.get();
                }
            } catch (CompletionException e) {
                RuntimeException cause = unwrap(e);
                LOG.log(System.Logger.Level.WARNING, () -> "wayback release " + source.release().releaseNumber()
                        + " (" + source.release().date() + ") failed fetching tile " + tileId(zoom, x, y) + ": "
                        + cause);
                if (firstFailure == null) {
                    firstFailure = cause;
                }
            }
        }

        if (best != null) {
            ScoredCandidate winner = best;
            return Optional.of(new WaybackFetchResult(winner.bytes(), winner.release().date(),
                    winner.release().releaseNumber(), winner.score()));
        }
        if (firstFailure != null) {
            throw firstFailure;
        }
        return Optional.empty();
    }

    private static CompletableFuture<Optional<ScoredCandidate>> fetchAndScoreAsync(
            ReleaseSource source, int zoom, int x, int y) {
        CompletableFuture<Optional<ScoredCandidate>> future = new CompletableFuture<>();
        Thread.ofVirtual().name("wayback-tile-" + source.release().releaseNumber()).start(() -> {
            try {
                Optional<byte[]> bytes = source.httpTileSource().fetchOptional(zoom, x, y);
                future.complete(bytes.map(b -> new ScoredCandidate(b, TileOcclusionScorer.score(b), source.release())));
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        return future;
    }

    private static RuntimeException unwrap(CompletionException e) {
        Throwable cause = e.getCause();
        return cause instanceof RuntimeException re ? re : e;
    }

    private static TileSourceSettings withUrlTemplate(TileSourceSettings base, String urlTemplate) {
        return new TileSourceSettings(urlTemplate, base.zoom(), base.maxTiles(), base.concurrency(),
                base.requestsPerSecond(), base.timeout(), base.userAgent(), base.maxRetries());
    }

    private static String tileId(int zoom, int x, int y) {
        return zoom + "/" + x + "/" + y;
    }

    /**
     * {@link #fetchWithDiagnostics(int, int, int)}'s result: the winning candidate's bytes plus
     * which release produced it and its occlusion score.
     *
     * @param bytes          the winning candidate's raw encoded image bytes
     * @param releaseDate    the winning release's {@link WaybackReleaseCatalog.WaybackRelease#date()}
     * @param releaseNumber  the winning release's {@link
     *                       WaybackReleaseCatalog.WaybackRelease#releaseNumber()}
     * @param occlusionScore the winning candidate's {@link TileOcclusionScorer} score, in {@code
     *                       [0,1]} (lower is better; this is the lowest among every
     *                       successfully-fetched candidate)
     */
    public record WaybackFetchResult(byte[] bytes, String releaseDate, String releaseNumber, double occlusionScore) {
    }

    private record ScoredCandidate(byte[] bytes, double score, WaybackReleaseCatalog.WaybackRelease release) {
    }

    private record ReleaseSource(WaybackReleaseCatalog.WaybackRelease release, HttpTileSource httpTileSource) {
    }
}
