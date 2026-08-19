package com.drones.vision.adapter.tiles;

import com.drones.vision.adapter.tiles.WaybackReleaseCatalog.WaybackRelease;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WaybackReleaseCatalogTest {

    @Test
    void defaultsHasTenGenuinelyDistinctReleases() {
        WaybackReleaseCatalog catalog = WaybackReleaseCatalog.defaults();
        assertEquals(10, catalog.releases().size());

        Set<String> releaseNumbers = catalog.releases().stream()
                .map(WaybackRelease::releaseNumber)
                .collect(Collectors.toSet());
        assertEquals(10, releaseNumbers.size(), "every release number in the curated default must be distinct");
    }

    @Test
    void defaultsMatchTheCuratedLiteralsFromTheWave11Investigation() {
        WaybackReleaseCatalog catalog = WaybackReleaseCatalog.defaults();

        assertContainsReleaseDated(catalog, "2014-02-20", "10");
        assertContainsReleaseDated(catalog, "2014-03-26", "4230");
        assertContainsReleaseDated(catalog, "2015-03-18", "15084");
        assertContainsReleaseDated(catalog, "2017-01-25", "9486");
        assertContainsReleaseDated(catalog, "2017-03-15", "29387");
        assertContainsReleaseDated(catalog, "2021-02-24", "9812");
        assertContainsReleaseDated(catalog, "2022-06-08", "44710");
        assertContainsReleaseDated(catalog, "2022-08-10", "17825");
        assertContainsReleaseDated(catalog, "2025-02-27", "34007");
        assertContainsReleaseDated(catalog, "2025-10-23", "20512");
    }

    @Test
    void everyDefaultReleaseUrlTemplateUsesTheWaybackWmtsPatternAndPlaceholders() {
        for (WaybackRelease release : WaybackReleaseCatalog.defaults().releases()) {
            assertTrue(release.urlTemplate().startsWith("https://wayback.maptiles.arcgis.com/"),
                    "expected the wayback.maptiles.arcgis.com host: " + release.urlTemplate());
            assertTrue(release.urlTemplate().contains("/World_Imagery/WMTS/1.0.0/default028mm/MapServer/tile/"
                    + release.releaseNumber() + "/"), "expected the release number baked into the path: "
                    + release.urlTemplate());
            assertTrue(release.urlTemplate().contains("{level}"));
            assertTrue(release.urlTemplate().contains("{row}"));
            assertTrue(release.urlTemplate().contains("{col}"));
        }
    }

    @Test
    void zxyUrlTemplateTranslatesWaybackPlaceholdersToTheHttpTileSourceConvention() {
        WaybackRelease release = new WaybackRelease("2022-08-10", "17825",
                "https://wayback.maptiles.arcgis.com/arcgis/rest/services/World_Imagery/WMTS/1.0.0/"
                        + "default028mm/MapServer/tile/17825/{level}/{row}/{col}");

        String translated = release.zxyUrlTemplate();

        assertEquals("https://wayback.maptiles.arcgis.com/arcgis/rest/services/World_Imagery/WMTS/1.0.0/"
                + "default028mm/MapServer/tile/17825/{z}/{y}/{x}", translated);
        assertTrue(translated.contains("{z}"));
        assertTrue(translated.contains("{x}"));
        assertTrue(translated.contains("{y}"));
    }

    @Test
    void constructorRejectsNullOrEmptyReleases() {
        assertThrows(NullPointerException.class, () -> new WaybackReleaseCatalog(null));
        assertThrows(IllegalArgumentException.class, () -> new WaybackReleaseCatalog(List.of()));
    }

    @Test
    void releaseRejectsBlankFields() {
        assertThrows(IllegalArgumentException.class,
                () -> new WaybackRelease(" ", "10", "https://x/{level}/{row}/{col}"));
        assertThrows(IllegalArgumentException.class,
                () -> new WaybackRelease("2014-02-20", " ", "https://x/{level}/{row}/{col}"));
        assertThrows(IllegalArgumentException.class,
                () -> new WaybackRelease("2014-02-20", "10", " "));
    }

    @Test
    void releaseRejectsUrlTemplateMissingAPlaceholder() {
        assertThrows(IllegalArgumentException.class,
                () -> new WaybackRelease("2014-02-20", "10", "https://x/{level}/{row}")); // missing {col}
    }

    private static void assertContainsReleaseDated(WaybackReleaseCatalog catalog, String date, String releaseNumber) {
        boolean found = catalog.releases().stream()
                .anyMatch(r -> r.date().equals(date) && r.releaseNumber().equals(releaseNumber));
        assertTrue(found, "expected a release dated " + date + " with releaseNumber " + releaseNumber);
    }
}
