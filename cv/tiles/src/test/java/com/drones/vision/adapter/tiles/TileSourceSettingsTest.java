package com.drones.vision.adapter.tiles;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TileSourceSettingsTest {

    private static TileSourceSettings valid() {
        return TileSourceSettings.defaults();
    }

    @Test
    void defaultsMatchTheDocumentedLiterals() {
        TileSourceSettings settings = TileSourceSettings.defaults();
        assertEquals("https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}",
                settings.urlTemplate());
        assertEquals(17, settings.zoom());
        assertEquals(50_000, settings.maxTiles());
        assertEquals(4, settings.concurrency());
        assertEquals(20.0, settings.requestsPerSecond());
        assertEquals(Duration.ofSeconds(10), settings.timeout());
        assertEquals("vision-geo/0.0.2", settings.userAgent());
        assertEquals(5, settings.maxRetries());
    }

    @Test
    void rejectsBlankUrlTemplate() {
        assertThrows(IllegalArgumentException.class, () -> withUrlTemplate(" "));
    }

    @Test
    void rejectsUrlTemplateMissingAPlaceholder() {
        assertThrows(IllegalArgumentException.class,
                () -> withUrlTemplate("https://example.com/tiles/{z}/{x}")); // missing {y}
    }

    @Test
    void rejectsZoomOutOfRange() {
        TileSourceSettings d = valid();
        assertThrows(IllegalArgumentException.class, () -> new TileSourceSettings(d.urlTemplate(), -1,
                d.maxTiles(), d.concurrency(), d.requestsPerSecond(), d.timeout(), d.userAgent(), d.maxRetries()));
        assertThrows(IllegalArgumentException.class, () -> new TileSourceSettings(d.urlTemplate(), 23,
                d.maxTiles(), d.concurrency(), d.requestsPerSecond(), d.timeout(), d.userAgent(), d.maxRetries()));
    }

    @Test
    void rejectsNonPositiveMaxTiles() {
        TileSourceSettings d = valid();
        assertThrows(IllegalArgumentException.class, () -> new TileSourceSettings(d.urlTemplate(), d.zoom(),
                0, d.concurrency(), d.requestsPerSecond(), d.timeout(), d.userAgent(), d.maxRetries()));
    }

    @Test
    void rejectsNonPositiveConcurrency() {
        TileSourceSettings d = valid();
        assertThrows(IllegalArgumentException.class, () -> new TileSourceSettings(d.urlTemplate(), d.zoom(),
                d.maxTiles(), 0, d.requestsPerSecond(), d.timeout(), d.userAgent(), d.maxRetries()));
    }

    @Test
    void rejectsNonPositiveOrNonFiniteRequestsPerSecond() {
        TileSourceSettings d = valid();
        assertThrows(IllegalArgumentException.class, () -> new TileSourceSettings(d.urlTemplate(), d.zoom(),
                d.maxTiles(), d.concurrency(), 0.0, d.timeout(), d.userAgent(), d.maxRetries()));
        assertThrows(IllegalArgumentException.class, () -> new TileSourceSettings(d.urlTemplate(), d.zoom(),
                d.maxTiles(), d.concurrency(), Double.NaN, d.timeout(), d.userAgent(), d.maxRetries()));
    }

    @Test
    void rejectsNullOrNonPositiveTimeout() {
        TileSourceSettings d = valid();
        assertThrows(NullPointerException.class, () -> new TileSourceSettings(d.urlTemplate(), d.zoom(),
                d.maxTiles(), d.concurrency(), d.requestsPerSecond(), null, d.userAgent(), d.maxRetries()));
        assertThrows(IllegalArgumentException.class, () -> new TileSourceSettings(d.urlTemplate(), d.zoom(),
                d.maxTiles(), d.concurrency(), d.requestsPerSecond(), Duration.ZERO, d.userAgent(), d.maxRetries()));
    }

    @Test
    void rejectsBlankUserAgent() {
        TileSourceSettings d = valid();
        assertThrows(IllegalArgumentException.class, () -> new TileSourceSettings(d.urlTemplate(), d.zoom(),
                d.maxTiles(), d.concurrency(), d.requestsPerSecond(), d.timeout(), " ", d.maxRetries()));
    }

    @Test
    void rejectsNonPositiveMaxRetries() {
        TileSourceSettings d = valid();
        assertThrows(IllegalArgumentException.class, () -> new TileSourceSettings(d.urlTemplate(), d.zoom(),
                d.maxTiles(), d.concurrency(), d.requestsPerSecond(), d.timeout(), d.userAgent(), 0));
    }

    private static void withUrlTemplate(String urlTemplate) {
        TileSourceSettings d = valid();
        new TileSourceSettings(urlTemplate, d.zoom(), d.maxTiles(), d.concurrency(), d.requestsPerSecond(),
                d.timeout(), d.userAgent(), d.maxRetries());
    }
}
