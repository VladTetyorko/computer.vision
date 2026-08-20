package com.drones.vision.perception.domain.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Golden values below were computed independently with the standard OSM slippy-map formulas
 * (python's {@code math.floor}/{@code math.log}/{@code math.tan}) — see
 * docs/plans/active/VISUAL-GEO-V2-PLAN.md §5 H2b's own tile-grid pin.
 */
class TileGridTest {

    @Test
    void singleTileBoundingBoxYieldsExactlyOneTile() {
        // A bbox strictly inside z10 tile (550, 335)'s own square, so cover() must return exactly it.
        RegionBounds bounds = new RegionBounds(52.642966, 52.536175, 13.623047, 13.447266);

        List<TileCoordinate> tiles = TileGrid.cover(bounds, 10);

        assertEquals(List.of(new TileCoordinate(10, 550, 335)), tiles);
        assertEquals(1L, TileGrid.count(bounds, 10));
    }

    @Test
    void knownZ17BoundingBoxYieldsExactGoldenTileSet() {
        // The plan's own Poznyaky example bounds (§3.3), whose worked tile-id example (17/76687/44230)
        // falls inside this golden x:[76681,76691] y:[44223,44232] range.
        RegionBounds bounds = new RegionBounds(50.4020, 50.3860, 30.6400, 30.6120);

        List<TileCoordinate> tiles = TileGrid.cover(bounds, 17);

        assertEquals(110, tiles.size());
        assertEquals(110L, TileGrid.count(bounds, 17));
        assertTrue(tiles.contains(new TileCoordinate(17, 76687, 44230)));
        assertTrue(tiles.contains(new TileCoordinate(17, 76681, 44223)));
        assertTrue(tiles.contains(new TileCoordinate(17, 76691, 44232)));
        assertTrue(tiles.stream().allMatch(t -> t.z() == 17
                && t.x() >= 76681 && t.x() <= 76691
                && t.y() >= 44223 && t.y() <= 44232));
    }

    @Test
    void rejectsNegativeZoom() {
        RegionBounds bounds = new RegionBounds(1.0, 0.0, 1.0, 0.0);
        assertThrows(IllegalArgumentException.class, () -> TileGrid.cover(bounds, -1));
        assertThrows(IllegalArgumentException.class, () -> TileGrid.count(bounds, -1));
    }
}
