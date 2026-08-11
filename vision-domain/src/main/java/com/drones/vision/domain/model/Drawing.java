package com.drones.vision.domain.model;

import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;

/**
 * A line, polygon, arrow, or text annotation on a {@link MapLayer} (docs/plans/done/MAP-REWORK-PLAN.md §2.1)
 * — the substrate for "plans" (a later slice groups drawings into an operation plan), modeled on
 * DELTA's Mission Control drawing tools (trenches, positions, routes).
 *
 * <p>{@link #points()}'s required size depends on {@link #kind()}: {@link DrawKind#LINE}/{@link
 * DrawKind#ARROW} need at least 2 (an open polyline), {@link DrawKind#POLYGON} needs at least 3
 * (a closed area, implicitly closed like {@link GeofenceZone#polygon()}), {@link DrawKind#TEXT}
 * needs exactly 1 (a single labeled point). {@link #label()} is required and non-blank only for
 * {@code TEXT} (the text a text annotation shows); it is optional for every other kind (a
 * caption), where a blank value normalizes to {@code null}, mirroring {@link Mark#note()}.
 *
 * @param id         typed drawing identity
 * @param layerId    the layer this drawing lives on
 * @param kind        the geometry shape
 * @param points      vertices; count constrained by {@code kind} (see class javadoc); defensively
 *                    copied
 * @param label       required non-blank for {@code TEXT}, optional otherwise (blank normalizes to
 *                    {@code null}); at most {@value #MAX_LABEL_LENGTH} chars
 * @param colorToken  optional UI design-token name (e.g. {@code "accent"}, {@code "danger"}) —
 *                    <b>not</b> a hex value — lower-case-kebab, at most {@value
 *                    #MAX_COLOR_TOKEN_LENGTH} chars; blank normalizes to {@code null}
 * @param ownership   who created this drawing and which group it belongs to
 * @param createdAt   when this drawing was created; never changes afterward
 */
public record Drawing(DrawingId id, LayerId layerId, DrawKind kind, List<GeoPosition> points,
                       String label, String colorToken, Ownership ownership, Instant createdAt) {

    /** {@link DrawKind#LINE}/{@link DrawKind#ARROW} need at least this many points. */
    private static final int MIN_LINE_POINTS = 2;

    /** {@link DrawKind#POLYGON} needs at least this many points. */
    private static final int MIN_POLYGON_POINTS = 3;

    /** {@link DrawKind#TEXT} needs exactly this many points. */
    private static final int TEXT_POINTS = 1;

    /** A drawing label longer than this is almost certainly a pasted description, not a label. */
    public static final int MAX_LABEL_LENGTH = 120;

    /** A colour token is a short UI slug, not free text. */
    public static final int MAX_COLOR_TOKEN_LENGTH = 30;

    private static final Pattern KEBAB_CASE = Pattern.compile("[a-z0-9]+(-[a-z0-9]+)*");

    public Drawing {
        if (id == null) {
            throw new IllegalArgumentException("Drawing id must not be null");
        }
        if (layerId == null) {
            throw new IllegalArgumentException("Drawing layerId must not be null");
        }
        if (kind == null) {
            throw new IllegalArgumentException("Drawing kind must not be null");
        }
        if (points == null) {
            throw new IllegalArgumentException("Drawing points must not be null");
        }
        requireValidPointCount(kind, points);
        points = List.copyOf(points);
        label = requireValidLabel(kind, label);
        colorToken = requireValidColorToken(colorToken);
        if (ownership == null) {
            throw new IllegalArgumentException("Drawing ownership must not be null");
        }
        if (createdAt == null) {
            throw new IllegalArgumentException("Drawing createdAt must not be null");
        }
    }

    private static void requireValidPointCount(DrawKind kind, List<GeoPosition> points) {
        int required = switch (kind) {
            case LINE, ARROW -> MIN_LINE_POINTS;
            case POLYGON -> MIN_POLYGON_POINTS;
            case TEXT -> TEXT_POINTS;
        };
        boolean valid = kind == DrawKind.TEXT ? points.size() == required : points.size() >= required;
        if (!valid) {
            throw new IllegalArgumentException(
                    "Drawing of kind " + kind + " requires " + (kind == DrawKind.TEXT ? "exactly " : "at least ")
                            + required + " points: " + points.size());
        }
    }

    private static String requireValidLabel(DrawKind kind, String label) {
        if (kind == DrawKind.TEXT) {
            if (label == null || label.isBlank()) {
                throw new IllegalArgumentException("Drawing label must not be blank for kind TEXT");
            }
        } else if (label != null && label.isBlank()) {
            label = null;
        }
        if (label != null && label.length() > MAX_LABEL_LENGTH) {
            throw new IllegalArgumentException(
                    "Drawing label must be at most " + MAX_LABEL_LENGTH + " characters: " + label.length());
        }
        return label;
    }

    private static String requireValidColorToken(String colorToken) {
        if (colorToken != null && colorToken.isBlank()) {
            colorToken = null;
        }
        if (colorToken == null) {
            return null;
        }
        if (colorToken.length() > MAX_COLOR_TOKEN_LENGTH) {
            throw new IllegalArgumentException(
                    "Drawing colorToken must be at most " + MAX_COLOR_TOKEN_LENGTH + " characters: "
                            + colorToken.length());
        }
        if (!KEBAB_CASE.matcher(colorToken).matches()) {
            throw new IllegalArgumentException("Drawing colorToken must be lower-case-kebab: " + colorToken);
        }
        return colorToken;
    }

    /**
     * Returns a copy of this drawing with a replaced geometry.
     *
     * <p>Identity, kind, label/colour, ownership and creation time are unchanged — dragging a
     * vertex is its own act, independent of relabeling or recolouring ({@link
     * #withDetails(String, String)}), mirroring how {@link Mark#withPosition} and {@link
     * #withDetails} stay separate.
     *
     * @param points the replacement points; count must satisfy this drawing's {@code kind}
     * @return a new {@code Drawing} with {@code points} replaced
     */
    public Drawing withGeometry(List<GeoPosition> points) {
        return new Drawing(id, layerId, kind, points, label, colorToken, ownership, createdAt);
    }

    /**
     * Returns a copy of this drawing with edited descriptive fields.
     *
     * <p>Identity, layer, kind, geometry, ownership and creation time are deliberately not
     * editable here.
     *
     * @param label      the replacement label; required non-blank for {@code TEXT}, optional
     *                   otherwise (blank normalizes to {@code null})
     * @param colorToken the replacement colour token; optional (blank normalizes to {@code null})
     * @return a new {@code Drawing} with {@code label}/{@code colorToken} replaced
     */
    public Drawing withDetails(String label, String colorToken) {
        return new Drawing(id, layerId, kind, points, label, colorToken, ownership, createdAt);
    }
}
