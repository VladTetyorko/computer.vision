package com.drones.vision.adapter.tiles;

/**
 * One slippy-map tile coordinate at a given zoom level — {@link TileMath}'s own coordinate-pair
 * return type, distinct from {@code com.drones.vision.perception.domain.model.TileCoordinate}
 * (which additionally carries its own {@code z} field; a bare {@code (x, y)} pair here is
 * deliberately lighter since {@link TileMath}'s callers already know the zoom they asked for).
 *
 * @param x the tile's x coordinate at the zoom level it was computed for
 * @param y the tile's y coordinate at the zoom level it was computed for
 */
public record TileXY(int x, int y) {
}
