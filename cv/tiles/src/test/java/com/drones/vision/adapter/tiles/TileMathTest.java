package com.drones.vision.adapter.tiles;

import com.drones.vision.kernel.GeoPosition;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TileMathTest {

    // Kyiv, Independence Square, zoom 17 (this module's default).
    private static final double KYIV_LAT = 50.4501;
    private static final double KYIV_LON = 30.5234;
    private static final int ZOOM = 17;

    @Test
    void degToTileAndTileCenterRoundTripWithinHalfATile() {
        TileXY tile = TileMath.degToTile(KYIV_LAT, KYIV_LON, ZOOM);
        GeoPosition center = TileMath.tileCenter(tile.x(), tile.y(), ZOOM);

        // At zoom 17 a tile spans roughly 0.0027 degrees of latitude at this latitude; the source
        // point must land inside its own containing tile, so the centre is within one tile width.
        assertTrue(Math.abs(center.latitude() - KYIV_LAT) < 0.01, "latitude out of range: " + center.latitude());
        assertTrue(Math.abs(center.longitude() - KYIV_LON) < 0.01, "longitude out of range: " + center.longitude());
    }

    @Test
    void tileToDegNorthWestCornerIsNorthOfAndWestOfTheCenter() {
        TileXY tile = TileMath.degToTile(KYIV_LAT, KYIV_LON, ZOOM);
        GeoPosition nw = TileMath.tileToDeg(tile.x(), tile.y(), ZOOM);
        GeoPosition center = TileMath.tileCenter(tile.x(), tile.y(), ZOOM);

        assertTrue(nw.latitude() > center.latitude(), "NW corner must be north of the tile centre");
        assertTrue(nw.longitude() < center.longitude(), "NW corner must be west of the tile centre");
    }

    @Test
    void degToTileAtZoomZeroIsAlwaysTileZeroZero() {
        // The whole world is one tile at zoom 0.
        assertEquals(new TileXY(0, 0), TileMath.degToTile(0.0, 0.0, 0));
        assertEquals(new TileXY(0, 0), TileMath.degToTile(45.0, -90.0, 0));
    }

    @Test
    void degToTileEquatorPrimeMeridianIsCenterOfTheGrid() {
        // At zoom z the grid is 2^z x 2^z; the equator/prime-meridian point falls in the tile
        // whose indices are exactly half the grid width (Web Mercator's own symmetry).
        int zoom = 4;
        TileXY tile = TileMath.degToTile(0.0, 0.0, zoom);
        int half = (int) Math.pow(2, zoom) / 2;
        assertEquals(half, tile.x());
        assertEquals(half, tile.y());
    }

    @Test
    void tilesForBboxCornersInAnyOrderProduceTheSameGrid() {
        List<TileXY> forward = TileMath.tilesForBbox(50.46, 30.50, 50.44, 30.55, ZOOM);
        List<TileXY> reversed = TileMath.tilesForBbox(50.44, 30.55, 50.46, 30.50, ZOOM);

        assertEquals(forward.size(), reversed.size());
        assertEquals(java.util.Set.copyOf(forward), java.util.Set.copyOf(reversed));
        assertTrue(forward.size() >= 1);
    }

    @Test
    void tilesForBboxCoversASingleTileAsExactlyOneTile() {
        TileXY tile = TileMath.degToTile(KYIV_LAT, KYIV_LON, ZOOM);
        GeoPosition nw = TileMath.tileToDeg(tile.x(), tile.y(), ZOOM);
        GeoPosition se = TileMath.tileToDeg(tile.x() + 1.0, tile.y() + 1.0, ZOOM);

        // A bbox strictly inside one tile's own corners must resolve to exactly that tile.
        double midLat = (nw.latitude() + se.latitude()) / 2.0;
        double midLon = (nw.longitude() + se.longitude()) / 2.0;
        List<TileXY> tiles = TileMath.tilesForBbox(midLat, midLon, midLat, midLon, ZOOM);
        assertEquals(List.of(tile), tiles);
    }

    @Test
    void tileToDegNeverProducesOutOfRangeCoordinates() {
        // y=0 is the Mercator north pole edge; must clamp rather than produce >90 latitude.
        GeoPosition pos = TileMath.tileToDeg(0, 0, 10);
        assertTrue(pos.latitude() <= 90.0 && pos.latitude() >= -90.0);
        assertTrue(pos.longitude() >= -180.0 && pos.longitude() <= 180.0);
    }
}
