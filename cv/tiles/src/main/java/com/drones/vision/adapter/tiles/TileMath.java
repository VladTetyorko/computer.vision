package com.drones.vision.adapter.tiles;

import com.drones.vision.kernel.GeoPosition;

import java.util.ArrayList;
import java.util.List;

/**
 * Standard slippy-map (Web Mercator) tile math — ported line-for-line from the Wave 0 feasibility
 * spike's {@code cv-service/spikes/geo/geomath.py} (the same formulas every other slippy-map tile
 * source, including {@code vision-web}'s own {@code shared/map/tile-cache/leaflet-loader.ts}, uses;
 * harvested from {@code feat/visual-geo}'s {@code adapter-tiles}, docs/plans/active/
 * VISUAL-GEO-V2-PLAN.md §1.3). Pure, stateless; private constructor, static methods only — no
 * interface, since there is exactly one way to do slippy-map tile math and no substitution point
 * (java-clean-code SKILL.md §1).
 *
 * <h2>Deliberate duplication</h2>
 * {@code contexts/vision-perception}'s {@code TileGrid} needs the same degree&harr;tile conversion
 * to turn a region's bounding box into a tile list, but that context may never depend on any {@code
 * cv/tiles}-style adapter module (the domain&larr;application&larr;adapters dependency rule,
 * ArchUnit-enforced) — so it carries its own small, self-contained copy of just the piece it needs,
 * rather than this class being shared across the boundary. This class exists for the fetch side:
 * translating a tile identity back to its geographic footprint ({@link #tileToDeg}/{@link
 * #tileCenter}), which {@code TileGrid} itself has no need for.
 */
public final class TileMath {

    private TileMath() {
    }

    /**
     * The slippy-map tile containing {@code (latDeg, lonDeg)} at {@code zoom} (Web Mercator
     * projection, standard {@code lon2tile}/{@code lat2tile} formula).
     *
     * @param latDeg latitude in degrees
     * @param lonDeg longitude in degrees
     * @param zoom   the zoom level; tile indices range {@code [0, 2^zoom)} at any valid latitude
     * @return the containing tile's {@code (x, y)}
     */
    public static TileXY degToTile(double latDeg, double lonDeg, int zoom) {
        double latRad = Math.toRadians(latDeg);
        double n = Math.pow(2, zoom);
        int x = (int) ((lonDeg + 180.0) / 360.0 * n);
        int y = (int) ((1.0 - asinh(Math.tan(latRad)) / Math.PI) / 2.0 * n);
        return new TileXY(x, y);
    }

    /**
     * The latitude/longitude of the north-west corner of tile {@code (x, y)} at {@code zoom}.
     * {@code x}/{@code y} deliberately accept fractional values (mirroring the Python spike's own
     * signature) so a caller can evaluate a half-tile offset — e.g. {@code x + 0.5}.
     *
     * @param x    the tile's x coordinate at {@code zoom} (fractional allowed)
     * @param y    the tile's y coordinate at {@code zoom} (fractional allowed)
     * @param zoom the zoom level
     * @return the north-west corner's position ({@code altitudeMeters} always {@code null})
     */
    public static GeoPosition tileToDeg(double x, double y, int zoom) {
        double n = Math.pow(2, zoom);
        double lonDeg = x / n * 360.0 - 180.0;
        double latRad = Math.atan(Math.sinh(Math.PI * (1 - 2 * y / n)));
        double latDeg = Math.toDegrees(latRad);
        return new GeoPosition(clampLatitude(latDeg), clampLongitude(lonDeg), null);
    }

    /**
     * The geographic centre of tile {@code (x, y)} at {@code zoom} — the midpoint of its north-west
     * and south-east corners.
     *
     * @param x    the tile's x coordinate at {@code zoom}
     * @param y    the tile's y coordinate at {@code zoom}
     * @param zoom the zoom level
     * @return the tile centre's position ({@code altitudeMeters} always {@code null})
     */
    public static GeoPosition tileCenter(int x, int y, int zoom) {
        GeoPosition nw = tileToDeg(x, y, zoom);
        GeoPosition se = tileToDeg(x + 1.0, y + 1.0, zoom);
        return new GeoPosition((nw.latitude() + se.latitude()) / 2.0, (nw.longitude() + se.longitude()) / 2.0, null);
    }

    /**
     * Every tile covering a bounding box at {@code zoom}, corners given in any order — mirrors the
     * Python spike's {@code tiles_for_bbox}. Returns a plain rectangular tile grid (the bbox's own
     * tile-aligned extent), not a polygon-exact selection: over-covering a region's true boundary by
     * up to half a tile per edge is the same trade-off every slippy-map tile fetcher in this repo
     * makes (deg2tile/tile2deg round to whole tile indices by construction).
     *
     * @param lat1 one corner's latitude
     * @param lon1 one corner's longitude
     * @param lat2 the opposite corner's latitude
     * @param lon2 the opposite corner's longitude
     * @param zoom the zoom level
     * @return every {@code (x, y)} tile covering the bbox, ordered south-to-north then west-to-east
     */
    public static List<TileXY> tilesForBbox(double lat1, double lon1, double lat2, double lon2, int zoom) {
        double south = Math.min(lat1, lat2);
        double north = Math.max(lat1, lat2);
        double west = Math.min(lon1, lon2);
        double east = Math.max(lon1, lon2);

        TileXY nw = degToTile(north, west, zoom);
        TileXY se = degToTile(south, east, zoom);
        int xLo = Math.min(nw.x(), se.x());
        int xHi = Math.max(nw.x(), se.x());
        int yLo = Math.min(nw.y(), se.y());
        int yHi = Math.max(nw.y(), se.y());

        List<TileXY> tiles = new ArrayList<>((xHi - xLo + 1) * (yHi - yLo + 1));
        for (int y = yLo; y <= yHi; y++) {
            for (int x = xLo; x <= xHi; x++) {
                tiles.add(new TileXY(x, y));
            }
        }
        return tiles;
    }

    /** {@code Math} has no {@code asinh}; the standard identity {@code ln(x + sqrt(x*x + 1))}. */
    private static double asinh(double x) {
        return Math.log(x + Math.sqrt(x * x + 1.0));
    }

    /** Guards against a north-pole-adjacent Mercator {@code atan(sinh(...))} rounding a hair past 90. */
    private static double clampLatitude(double latDeg) {
        return Math.max(-90.0, Math.min(90.0, latDeg));
    }

    /** Guards against an antimeridian-adjacent tile edge rounding a hair past &plusmn;180. */
    private static double clampLongitude(double lonDeg) {
        return Math.max(-180.0, Math.min(180.0, lonDeg));
    }
}
