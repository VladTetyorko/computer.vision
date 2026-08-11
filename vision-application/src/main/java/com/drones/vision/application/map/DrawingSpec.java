package com.drones.vision.application.map;

import com.drones.vision.domain.model.DrawKind;
import com.drones.vision.domain.model.Drawing;
import com.drones.vision.domain.model.GeoPosition;
import com.drones.vision.domain.model.LayerId;

import java.util.List;

/**
 * Everything needed to create a {@link Drawing} (docs/plans/done/MAP-REWORK-PLAN.md §3/§4.2).
 *
 * <p>Duplicates {@link Drawing}'s own per-{@link DrawKind} point-count invariant and its
 * TEXT-requires-a-label rule so a malformed request fails fast with a spec-specific message before
 * touching the repository, mirroring {@code MarkSpec}'s own duplication of {@code Mark}'s
 * invariants; {@code colorToken}'s kebab-case/length shape is left to {@link Drawing}'s own compact
 * constructor to catch (still a 400, just one call later — a UI only ever sends a fixed set of
 * design-token names here, unlike free-text label/points).
 *
 * @param layerId    the layer this drawing lands on, or {@code null} to use the creator's default
 *                   layer (see {@link LayerResolver#defaultLayerFor})
 * @param kind        the geometry shape
 * @param points      vertices; count constrained by {@code kind} exactly like {@link Drawing}
 * @param label       required non-blank for {@link DrawKind#TEXT}, optional otherwise (blank
 *                   normalizes to {@code null})
 * @param colorToken  optional UI design-token name; blank normalizes to {@code null}
 */
public record DrawingSpec(LayerId layerId, DrawKind kind, List<GeoPosition> points, String label,
                           String colorToken) {

    public DrawingSpec {
        if (kind == null) {
            throw new IllegalArgumentException("DrawingSpec kind must not be null");
        }
        if (points == null) {
            throw new IllegalArgumentException("DrawingSpec points must not be null");
        }
        points = List.copyOf(points);
        requireValidPointCount(kind, points);
        if (kind == DrawKind.TEXT) {
            if (label == null || label.isBlank()) {
                throw new IllegalArgumentException("DrawingSpec label must not be blank for kind TEXT");
            }
        } else if (label != null && label.isBlank()) {
            label = null;
        }
        if (colorToken != null && colorToken.isBlank()) {
            colorToken = null;
        }
    }

    private static void requireValidPointCount(DrawKind kind, List<GeoPosition> points) {
        int required = switch (kind) {
            case LINE, ARROW -> 2;
            case POLYGON -> 3;
            case TEXT -> 1;
        };
        boolean valid = kind == DrawKind.TEXT ? points.size() == required : points.size() >= required;
        if (!valid) {
            throw new IllegalArgumentException(
                    "DrawingSpec of kind " + kind + " requires " + (kind == DrawKind.TEXT ? "exactly " : "at least ")
                            + required + " points: " + points.size());
        }
    }
}
