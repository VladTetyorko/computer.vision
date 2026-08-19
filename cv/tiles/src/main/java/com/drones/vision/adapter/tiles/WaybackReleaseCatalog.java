package com.drones.vision.adapter.tiles;

import java.util.List;
import java.util.Objects;

/**
 * A small, curated, compile-time list of Esri World Imagery "Wayback" historical release captures
 * (harvested from {@code feat/visual-geo}'s {@code adapter-tiles}, docs/plans/active/
 * VISUAL-GEO-V2-PLAN.md §1.3/§3.6's {@code wayback-multi-date} knob) — each a distinct dated
 * snapshot of the same World Imagery basemap {@link HttpTileSource}'s default already fetches,
 * served from a sibling Esri endpoint ({@code wayback.maptiles.arcgis.com}) under the same Esri
 * Master Agreement already covering this project's existing tile usage.
 *
 * <h2>Why curated, not the full ~196-release archive</h2>
 * An earlier investigation fetched all 196 historical releases for one real tile ({@code
 * 17/76687/44230}, the Chavdar tile) and found only <b>10 genuinely distinct captures</b> — Esri
 * only updates specific regions per release, so most releases repeat the prior image for any given
 * tile. Querying all 196 would multiply outbound request volume ~20x for no benefit and is impolite
 * to a free service. {@link #defaults()} ships exactly those 10 releases as a reasonable global
 * default: release <em>numbers</em> are a single global sequence Esri assigns across the whole
 * Wayback archive (not scoped to one tile), so they are valid to query for any tile worldwide —
 * though whether a given tile actually changed between two release dates is naturally
 * tile-specific.
 *
 * @param releases every configured release, in the order {@link WaybackTileSource} queries them (an
 *                 order with no significance to selection — every release is queried and scored the
 *                 same way regardless of position); must not be null or empty
 */
public record WaybackReleaseCatalog(List<WaybackRelease> releases) {

    public WaybackReleaseCatalog {
        Objects.requireNonNull(releases, "releases must not be null");
        releases = List.copyOf(releases);
        if (releases.isEmpty()) {
            throw new IllegalArgumentException("releases must not be empty");
        }
    }

    /**
     * The 10 releases confirmed as genuinely distinct captures for the real Chavdar tile ({@code
     * 17/76687/44230}), spanning 2014 (empty field, pre-construction) through 2025.
     */
    public static WaybackReleaseCatalog defaults() {
        return new WaybackReleaseCatalog(List.of(
                release("2014-02-20", "10"),
                release("2014-03-26", "4230"),
                release("2015-03-18", "15084"),
                release("2017-01-25", "9486"),
                release("2017-03-15", "29387"),
                release("2021-02-24", "9812"),
                release("2022-06-08", "44710"),
                release("2022-08-10", "17825"),
                release("2025-02-27", "34007"),
                release("2025-10-23", "20512")));
    }

    private static WaybackRelease release(String date, String releaseNumber) {
        String urlTemplate = "https://wayback.maptiles.arcgis.com/arcgis/rest/services/World_Imagery/WMTS/1.0.0/"
                + "default028mm/MapServer/tile/" + releaseNumber + "/{level}/{row}/{col}";
        return new WaybackRelease(date, releaseNumber, urlTemplate);
    }

    /**
     * One Wayback release: a dated capture identified by Esri's own release number, with its own
     * tile URL template.
     *
     * @param date          the capture date, {@code yyyy-MM-dd}, for diagnostics/logging only —
     *                      never parsed or compared
     * @param releaseNumber Esri's global release-number identifier for this capture (part of the URL
     *                      path, not a placeholder — Wayback URLs bake the release number in
     *                      directly rather than templating it)
     * @param urlTemplate   this release's tile URL template, using Wayback's <b>own</b> placeholder
     *                      names {@code {level}}/{@code {row}}/{@code {col}} — deliberately
     *                      <b>different</b> from {@link TileSourceSettings}'s {@code {z}}/{@code
     *                      {x}}/{@code {y}} convention: standard WMTS TileMatrix/TileRow/TileCol
     *                      naming, {@code row} being the vertical (latitude) index and {@code col}
     *                      the horizontal (longitude) index, exactly like {@code y}/{@code x}
     *                      elsewhere in this module. {@link #zxyUrlTemplate()} performs this
     *                      translation so the template can be handed to an internal {@link
     *                      HttpTileSource} unmodified.
     */
    public record WaybackRelease(String date, String releaseNumber, String urlTemplate) {

        private static final String LEVEL_PLACEHOLDER = "{level}";
        private static final String ROW_PLACEHOLDER = "{row}";
        private static final String COL_PLACEHOLDER = "{col}";

        public WaybackRelease {
            if (date == null || date.isBlank()) {
                throw new IllegalArgumentException("date must not be blank");
            }
            if (releaseNumber == null || releaseNumber.isBlank()) {
                throw new IllegalArgumentException("releaseNumber must not be blank");
            }
            if (urlTemplate == null || urlTemplate.isBlank()) {
                throw new IllegalArgumentException("urlTemplate must not be blank");
            }
            if (!urlTemplate.contains(LEVEL_PLACEHOLDER) || !urlTemplate.contains(ROW_PLACEHOLDER)
                    || !urlTemplate.contains(COL_PLACEHOLDER)) {
                throw new IllegalArgumentException(
                        "urlTemplate must contain {level}, {row} and {col} placeholders: " + urlTemplate);
            }
        }

        /**
         * This release's URL template translated from Wayback's {@code {level}}/{@code {row}}/
         * {@code {col}} placeholders into {@link TileSourceSettings}'s {@code {z}}/{@code {x}}/
         * {@code {y}} convention ({@code level -> z}, {@code row -> y}, {@code col -> x}), so it can
         * be passed straight into a {@link TileSourceSettings#urlTemplate()} and fetched by a plain
         * {@link HttpTileSource} with zero changes to that class.
         */
        String zxyUrlTemplate() {
            return urlTemplate
                    .replace(LEVEL_PLACEHOLDER, "{z}")
                    .replace(ROW_PLACEHOLDER, "{y}")
                    .replace(COL_PLACEHOLDER, "{x}");
        }
    }
}
