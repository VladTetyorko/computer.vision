package com.drones.vision.adapter.publishhls;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for the shared {@code /get} query-URL formatting both {@link
 * MediamtxStreamPublisher#playbackUrl} and {@link MediamtxReplayFrameExtractor} delegate to.
 * {@link MediamtxStreamPublisherTest}'s own {@code playbackUrl*} tests exercise the same formatting
 * indirectly (through the port method); these tests exercise {@link MediamtxPlaybackUrls} directly
 * so the shared helper has its own coverage independent of either caller.
 */
class MediamtxPlaybackUrlsTest {

    @Test
    void formatsPathStartAndDurationQueryParameters() {
        String url = MediamtxPlaybackUrls.getUrl(URI.create("http://localhost:19996"),
                "11111111-1111-1111-1111-111111111111", Instant.parse("2026-01-15T10:00:00Z"), 30L);

        assertEquals("http://localhost:19996/get"
                + "?path=11111111-1111-1111-1111-111111111111&start=2026-01-15T10:00:00Z&duration=30", url);
    }

    @Test
    void toleratesTrailingSlashOnPlaybackBase() {
        String url = MediamtxPlaybackUrls.getUrl(URI.create("http://localhost:19996/"),
                "11111111-1111-1111-1111-111111111111", Instant.parse("2026-01-15T10:00:00Z"), 30L);

        assertEquals("http://localhost:19996/get"
                + "?path=11111111-1111-1111-1111-111111111111&start=2026-01-15T10:00:00Z&duration=30", url);
    }

    /**
     * {@link MediamtxReplayFrameExtractor} always requests a one-second window — this is the exact
     * query shape it builds (docs/CV-TRAINING-V2-PLAN.md §6: "one-second window starting at the
     * wanted instant").
     */
    @Test
    void formatsOneSecondWindowAsUsedByTheReplayFrameExtractor() {
        String url = MediamtxPlaybackUrls.getUrl(URI.create("http://localhost:19996"),
                "cam-1", Instant.parse("2026-01-15T10:00:00.500Z"), 1L);

        assertEquals("http://localhost:19996/get?path=cam-1&start=2026-01-15T10:00:00.500Z&duration=1", url);
    }

    @Test
    void startUsesInstantToStringVerbatimIncludingSubSecondPrecision() {
        Instant start = Instant.parse("2026-01-15T10:00:00.123456789Z");

        String url = MediamtxPlaybackUrls.getUrl(URI.create("http://localhost:19996"), "cam-1", start, 1L);

        assertEquals("http://localhost:19996/get?path=cam-1&start=" + start + "&duration=1", url);
    }
}
