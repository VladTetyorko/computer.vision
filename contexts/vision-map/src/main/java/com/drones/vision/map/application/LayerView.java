package com.drones.vision.map.application;

import com.drones.vision.map.domain.model.AccessLevel;
import com.drones.vision.map.domain.model.MapLayer;

/**
 * One row of {@link MapLayerService#layers}: a layer the viewer may see, plus the highest {@link
 * AccessLevel} they hold on it (so a caller — {@code vision-api}'s response mapping — never has to
 * re-run {@link MapAccessPolicy} itself just to know whether to show a grants editor).
 *
 * @param layer    the visible layer
 * @param myAccess the viewer's own highest access level on it; never {@code null} — a layer this
 *                 record names was already filtered by {@link MapAccessPolicy#canView}
 */
public record LayerView(MapLayer layer, AccessLevel myAccess) {

    public LayerView {
        if (layer == null) {
            throw new IllegalArgumentException("LayerView layer must not be null");
        }
        if (myAccess == null) {
            throw new IllegalArgumentException("LayerView myAccess must not be null");
        }
    }
}
