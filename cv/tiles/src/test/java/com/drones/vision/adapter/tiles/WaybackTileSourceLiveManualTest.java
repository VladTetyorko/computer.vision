package com.drones.vision.adapter.tiles;

import com.drones.vision.adapter.tiles.WaybackTileSource.WaybackFetchResult;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Documents how to manually exercise {@link WaybackTileSource} against the REAL Esri Wayback
 * service. Not run in CI — real outbound internet access to two Esri endpoints is not guaranteed
 * there, and this deliberately makes ~11 real HTTP requests per run (10 Wayback releases + 1
 * single-date baseline) — hence {@code @Disabled}, same convention as {@code
 * FfmpegVideoSourceLiveManualTest} (video-input/rtsp).
 *
 * <h2>Manual run</h2>
 * <ol>
 *   <li>Confirm outbound internet access to {@code server.arcgisonline.com} and {@code
 *   wayback.maptiles.arcgis.com}.</li>
 *   <li>Remove (or comment out) the {@code @Disabled} annotation below and run:
 *       {@code ./mvnw -pl cv/tiles test -Dtest=WaybackTileSourceLiveManualTest}</li>
 *   <li>Read the printed report on stdout — which release {@link WaybackTileSource} picked, its
 *   occlusion score, and the naive single-date {@link HttpTileSource} baseline's own score, for the
 *   real validated Chavdar tile ({@code z=17 x=76687 y=44230}).</li>
 * </ol>
 *
 * <p>This intentionally does <b>not</b> hard-assert that Wayback strictly beats the baseline on
 * every run — a real network call against a live third-party service can behave differently than an
 * earlier preliminary investigation (a different release could win, imagery could change), and
 * papering over that with a rewritten assertion would defeat the point of a real acceptance check.
 * Both fetches succeeding is asserted; the actual comparison is reported, not enforced.
 */
class WaybackTileSourceLiveManualTest {

    private static final int ZOOM = 17;
    private static final int X = 76687;
    private static final int Y = 44230;

    @Disabled("hits the real Esri/Wayback services over the network -- see class javadoc for how to run manually")
    @Test
    void fetchesTheRealChavdarTileAndReportsWaybackVersusNaiveSingleDateBaseline() {
        WaybackTileSource waybackSource = new WaybackTileSource(
                WaybackReleaseCatalog.defaults(), WaybackTileSource.recommendedPerReleaseSettings());
        HttpTileSource baselineSource = new HttpTileSource(TileSourceSettings.defaults());

        Optional<WaybackFetchResult> waybackResult = waybackSource.fetchWithDiagnostics(ZOOM, X, Y);
        byte[] baselineResult = baselineSource.fetch(ZOOM, X, Y);

        assertTrue(waybackResult.isPresent(), "expected at least one Wayback release to cover " + tileId());

        double baselineScore = TileOcclusionScorer.score(baselineResult);
        WaybackFetchResult winner = waybackResult.get();

        System.out.println("=== WaybackTileSource vs naive single-date baseline, acceptance evidence ===");
        System.out.println("Tile: " + tileId());
        System.out.println("Wayback winner: release " + winner.releaseNumber() + " (" + winner.releaseDate() + ")");
        System.out.println("Wayback winner occlusion score: " + winner.occlusionScore());
        System.out.println("Naive single-date baseline occlusion score: " + baselineScore);
        System.out.println("Wayback bytes size: " + winner.bytes().length + " naive baseline bytes size: "
                + baselineResult.length);
        if (winner.occlusionScore() <= baselineScore) {
            System.out.println("RESULT: Wayback selection is no worse than the naive baseline ("
                    + winner.occlusionScore() + " <= " + baselineScore + ").");
        } else {
            System.out.println("RESULT: Wayback selection scored WORSE than the naive baseline this run ("
                    + winner.occlusionScore() + " > " + baselineScore + ") -- report this honestly, do not "
                    + "adjust the assertion to hide it.");
        }
    }

    private static String tileId() {
        return ZOOM + "/" + X + "/" + Y;
    }
}
