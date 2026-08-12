package com.drones.vision.flight.domain.model;

import com.drones.vision.kernel.GeoPosition;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeofenceZoneTest {

    /** A 10x10 square: lat/lon in [10,20]. */
    private static List<GeoPosition> square() {
        return List.of(
                new GeoPosition(10, 10, null),
                new GeoPosition(10, 20, null),
                new GeoPosition(20, 20, null),
                new GeoPosition(20, 10, null));
    }

    /**
     * A concave "chevron" polygon: a triangular notch cut into the left side, tip at (x=4,y=5),
     * where x=longitude, y=latitude. Traced (lat,lon): (0,0) -> (5,4) -> (10,0) -> (10,10) ->
     * (0,10) -> back to (0,0).
     */
    private static List<GeoPosition> chevron() {
        return List.of(
                new GeoPosition(0, 0, null),
                new GeoPosition(5, 4, null),
                new GeoPosition(10, 0, null),
                new GeoPosition(10, 10, null),
                new GeoPosition(0, 10, null));
    }

    private static GeofenceZone zone(ZoneKind kind, List<GeoPosition> polygon) {
        return new GeofenceZone(ZoneId.random(), "test zone", kind, polygon, null, true);
    }

    // --- Validation ------------------------------------------------------------

    @Test
    void rejectsNullId() {
        assertThrows(IllegalArgumentException.class,
                () -> new GeofenceZone(null, "zone", ZoneKind.KEEP_OUT, square(), null, true));
    }

    @Test
    void rejectsBlankName() {
        assertThrows(IllegalArgumentException.class,
                () -> new GeofenceZone(ZoneId.random(), "", ZoneKind.KEEP_OUT, square(), null, true));
        assertThrows(IllegalArgumentException.class,
                () -> new GeofenceZone(ZoneId.random(), null, ZoneKind.KEEP_OUT, square(), null, true));
    }

    @Test
    void rejectsNullKind() {
        assertThrows(IllegalArgumentException.class,
                () -> new GeofenceZone(ZoneId.random(), "zone", null, square(), null, true));
    }

    @Test
    void rejectsNullPolygon() {
        assertThrows(IllegalArgumentException.class,
                () -> new GeofenceZone(ZoneId.random(), "zone", ZoneKind.KEEP_OUT, null, null, true));
    }

    @Test
    void rejectsPolygonWithFewerThanThreeVertices() {
        assertThrows(IllegalArgumentException.class, () -> zone(ZoneKind.KEEP_OUT, List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> zone(ZoneKind.KEEP_OUT, List.of(new GeoPosition(0, 0, null))));
        assertThrows(IllegalArgumentException.class, () -> zone(ZoneKind.KEEP_OUT,
                List.of(new GeoPosition(0, 0, null), new GeoPosition(0, 1, null))));
    }

    @Test
    void acceptsExactlyThreeVertices() {
        GeofenceZone triangle = zone(ZoneKind.KEEP_OUT,
                List.of(new GeoPosition(0, 0, null), new GeoPosition(0, 10, null), new GeoPosition(10, 5, null)));

        assertEquals(3, triangle.polygon().size());
    }

    @Test
    void rejectsNegativeMaxAltitude() {
        assertThrows(IllegalArgumentException.class,
                () -> new GeofenceZone(ZoneId.random(), "zone", ZoneKind.KEEP_OUT, square(), -1.0, true));
    }

    @Test
    void acceptsNullMaxAltitude() {
        GeofenceZone withNoCeiling = new GeofenceZone(ZoneId.random(), "zone", ZoneKind.KEEP_OUT, square(), null, true);

        assertNull(withNoCeiling.maxAltitudeMeters());
    }

    @Test
    void polygonIsDefensivelyCopied() {
        List<GeoPosition> mutable = new ArrayList<>(square());

        GeofenceZone zone = zone(ZoneKind.KEEP_OUT, mutable);
        mutable.add(new GeoPosition(0, 0, null));

        assertEquals(4, zone.polygon().size(), "later mutation of the source list must not affect the zone");
        assertThrows(UnsupportedOperationException.class, () -> zone.polygon().add(new GeoPosition(0, 0, null)),
                "returned polygon list must be immutable");
    }

    // --- contains(): square -------------------------------------------------------

    @Test
    void containsIsTrueForAPointWellInsideTheSquare() {
        GeofenceZone zone = zone(ZoneKind.KEEP_OUT, square());

        assertTrue(zone.contains(new GeoPosition(15, 15, null)));
    }

    @Test
    void containsIsFalseForAPointWellOutsideTheSquare() {
        GeofenceZone zone = zone(ZoneKind.KEEP_OUT, square());

        assertFalse(zone.contains(new GeoPosition(5, 5, null)));
        assertFalse(zone.contains(new GeoPosition(15, 25, null)), "east of the square must be outside");
        assertFalse(zone.contains(new GeoPosition(25, 15, null)), "north of the square must be outside");
    }

    @Test
    void containsHandlesPointsJustEitherSideOfAVertex() {
        GeofenceZone zone = zone(ZoneKind.KEEP_OUT, square());

        // Corner at (lat=10, lon=10): nudging both coordinates up moves into the square,
        // nudging both down moves away from it.
        assertTrue(zone.contains(new GeoPosition(10.001, 10.001, null)),
                "just inside the corner must read as inside");
        assertFalse(zone.contains(new GeoPosition(9.999, 9.999, null)),
                "just outside the corner must read as outside");
    }

    @Test
    void containsRejectsNullPosition() {
        GeofenceZone zone = zone(ZoneKind.KEEP_OUT, square());

        assertThrows(IllegalArgumentException.class, () -> zone.contains(null));
    }

    // --- contains(): concave polygon -----------------------------------------------

    @Test
    void containsIsTrueInsideTheSolidPartOfAConcavePolygon() {
        GeofenceZone zone = zone(ZoneKind.KEEP_IN, chevron());

        // (lat=5.5, lon=8): well to the right of the notch, inside the solid body.
        assertTrue(zone.contains(new GeoPosition(5.5, 8, null)));
    }

    @Test
    void containsIsFalseInsideTheNotchOfAConcavePolygon() {
        GeofenceZone zone = zone(ZoneKind.KEEP_IN, chevron());

        // (lat=5.5, lon=2): left of the notch's tip -- inside the polygon's bounding box, but
        // outside the polygon itself, exactly what a naive bounding-box check would get wrong.
        assertFalse(zone.contains(new GeoPosition(5.5, 2, null)));
    }

    // --- Copy methods ------------------------------------------------------------

    @Test
    void withEnabledReturnsNewInstanceLeavingOtherFieldsUnchanged() {
        GeofenceZone original = zone(ZoneKind.KEEP_OUT, square());

        GeofenceZone disabled = original.withEnabled(false);

        assertFalse(disabled.enabled());
        assertTrue(original.enabled(), "original instance must be unchanged");
        assertEquals(original.id(), disabled.id());
        assertEquals(original.name(), disabled.name());
        assertEquals(original.kind(), disabled.kind());
        assertEquals(original.polygon(), disabled.polygon());
        assertEquals(original.maxAltitudeMeters(), disabled.maxAltitudeMeters());
    }

    @Test
    void withDetailsReturnsNewInstancePreservingIdAndEnabled() {
        GeofenceZone original = zone(ZoneKind.KEEP_OUT, square());
        List<GeoPosition> newPolygon =
                List.of(new GeoPosition(0, 0, null), new GeoPosition(0, 1, null), new GeoPosition(1, 0, null));

        GeofenceZone updated = original.withDetails("renamed", ZoneKind.KEEP_IN, newPolygon, 50.0);

        assertEquals("renamed", updated.name());
        assertEquals(ZoneKind.KEEP_IN, updated.kind());
        assertEquals(newPolygon, updated.polygon());
        assertEquals(50.0, updated.maxAltitudeMeters());
        assertEquals(original.id(), updated.id(), "identity must be preserved");
        assertEquals(original.enabled(), updated.enabled(), "enabled flag must be preserved");
        assertEquals("test zone", original.name(), "original instance must be unchanged");
    }

    @Test
    void withDetailsValidatesTheReplacementPolygon() {
        GeofenceZone original = zone(ZoneKind.KEEP_OUT, square());

        assertThrows(IllegalArgumentException.class,
                () -> original.withDetails("renamed", ZoneKind.KEEP_IN, List.of(), null));
    }
}
