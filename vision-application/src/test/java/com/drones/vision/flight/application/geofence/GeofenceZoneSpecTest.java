package com.drones.vision.flight.application.geofence;

import com.drones.vision.kernel.GeoPosition;
import com.drones.vision.flight.domain.model.ZoneKind;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GeofenceZoneSpecTest {

    private static List<GeoPosition> square() {
        return List.of(
                new GeoPosition(10, 10, null),
                new GeoPosition(10, 20, null),
                new GeoPosition(20, 20, null),
                new GeoPosition(20, 10, null));
    }

    @Test
    void rejectsBlankName() {
        assertThrows(IllegalArgumentException.class,
                () -> new GeofenceZoneSpec(" ", ZoneKind.KEEP_OUT, square(), null, true));
        assertThrows(IllegalArgumentException.class,
                () -> new GeofenceZoneSpec(null, ZoneKind.KEEP_OUT, square(), null, true));
    }

    @Test
    void rejectsNullKind() {
        assertThrows(IllegalArgumentException.class,
                () -> new GeofenceZoneSpec("zone", null, square(), null, true));
    }

    @Test
    void rejectsNullOrTooSmallPolygon() {
        assertThrows(IllegalArgumentException.class,
                () -> new GeofenceZoneSpec("zone", ZoneKind.KEEP_OUT, null, null, true));
        assertThrows(IllegalArgumentException.class,
                () -> new GeofenceZoneSpec("zone", ZoneKind.KEEP_OUT, List.of(), null, true));
        assertThrows(IllegalArgumentException.class, () -> new GeofenceZoneSpec("zone", ZoneKind.KEEP_OUT,
                List.of(new GeoPosition(0, 0, null), new GeoPosition(0, 1, null)), null, true));
    }

    @Test
    void rejectsNegativeMaxAltitude() {
        assertThrows(IllegalArgumentException.class,
                () -> new GeofenceZoneSpec("zone", ZoneKind.KEEP_OUT, square(), -1.0, true));
    }

    @Test
    void acceptsNullMaxAltitude() {
        GeofenceZoneSpec spec = new GeofenceZoneSpec("zone", ZoneKind.KEEP_OUT, square(), null, true);

        assertNull(spec.maxAltitudeMeters());
    }

    @Test
    void polygonIsDefensivelyCopied() {
        List<GeoPosition> mutable = new ArrayList<>(square());

        GeofenceZoneSpec spec = new GeofenceZoneSpec("zone", ZoneKind.KEEP_OUT, mutable, null, true);
        mutable.add(new GeoPosition(0, 0, null));

        assertEquals(4, spec.polygon().size(), "later mutation of the source list must not affect the spec");
        assertThrows(UnsupportedOperationException.class, () -> spec.polygon().add(new GeoPosition(0, 0, null)),
                "returned polygon list must be immutable");
    }
}
