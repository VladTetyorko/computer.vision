package com.drones.vision.kernel;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class VisualFixTest {

    private static final Instant FRAME_AT = Instant.parse("2026-08-19T12:00:00Z");
    private static final GeoPosition POSITION = new GeoPosition(50.45, 30.52, null);
    private static final VisualFixEvidence EVIDENCE =
            new VisualFixEvidence(10, 50, 20, 0.4, 0.3, 2.0, true, true, 5, 50.0, true, 10.0, 5, 1.0);

    private static VisualFix accepted() {
        return new VisualFix(FRAME_AT, POSITION, 214.6, 18.4, 96.2, "kyiv-pozniaky", "17/76687/44230", "",
                EVIDENCE, 200, 480);
    }

    private static VisualFix refused() {
        return new VisualFix(FRAME_AT, null, null, null, null, "kyiv-pozniaky", "", "LOW_TEXTURE", EVIDENCE, 200,
                480);
    }

    @Test
    void storesEveryFieldOnAnAcceptedFix() {
        VisualFix fix = accepted();

        assertEquals(FRAME_AT, fix.frameAt());
        assertEquals(POSITION, fix.position());
        assertEquals(214.6, fix.yawDegrees());
        assertEquals(18.4, fix.radiusMeters());
        assertEquals(96.2, fix.impliedAglMeters());
        assertEquals("kyiv-pozniaky", fix.regionId());
        assertEquals("17/76687/44230", fix.tileId());
        assertEquals("", fix.refusal());
        assertEquals(EVIDENCE, fix.evidence());
        assertEquals(200, fix.telemetryAgeMillis());
        assertEquals(480, fix.latencyMillis());
    }

    @Test
    void storesARefusedFixWithNullPositionAndNonEmptyRefusal() {
        VisualFix fix = refused();

        assertNull(fix.position());
        assertEquals("LOW_TEXTURE", fix.refusal());
    }

    @Test
    void rejectsNullFrameAt() {
        assertThrows(IllegalArgumentException.class,
                () -> new VisualFix(null, POSITION, null, 18.4, null, "", "", "", EVIDENCE, 0, 0));
    }

    @Test
    void rejectsAPositionWithEmptyRefusal() {
        // position != null but refusal is also non-empty -- the invariant is exactly one, not both.
        assertThrows(IllegalArgumentException.class,
                () -> new VisualFix(FRAME_AT, POSITION, null, 18.4, null, "", "", "LOW_TEXTURE", EVIDENCE, 0, 0));
    }

    @Test
    void rejectsANullPositionWithEmptyRefusal() {
        assertThrows(IllegalArgumentException.class,
                () -> new VisualFix(FRAME_AT, null, null, null, null, "", "", "", EVIDENCE, 0, 0));
    }

    @Test
    void rejectsNullRegionIdTileIdOrRefusal() {
        assertThrows(IllegalArgumentException.class,
                () -> new VisualFix(FRAME_AT, POSITION, null, 18.4, null, null, "", "", EVIDENCE, 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new VisualFix(FRAME_AT, POSITION, null, 18.4, null, "", null, "", EVIDENCE, 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new VisualFix(FRAME_AT, null, null, null, null, "", "", null, EVIDENCE, 0, 0));
    }

    @Test
    void rejectsNullEvidence() {
        assertThrows(IllegalArgumentException.class,
                () -> new VisualFix(FRAME_AT, POSITION, null, 18.4, null, "", "", "", null, 0, 0));
    }

    @Test
    void rejectsNegativeRadiusMeters() {
        assertThrows(IllegalArgumentException.class,
                () -> new VisualFix(FRAME_AT, POSITION, null, -1.0, null, "", "", "", EVIDENCE, 0, 0));
    }

    @Test
    void rejectsNonFiniteYawDegrees() {
        assertThrows(IllegalArgumentException.class,
                () -> new VisualFix(FRAME_AT, POSITION, Double.NaN, 18.4, null, "", "", "", EVIDENCE, 0, 0));
    }

    @Test
    void rejectsAPositionCarryingAnAltitude() {
        GeoPosition withAltitude = new GeoPosition(50.45, 30.52, 100.0);
        assertThrows(IllegalArgumentException.class,
                () -> new VisualFix(FRAME_AT, withAltitude, null, 18.4, null, "", "", "", EVIDENCE, 0, 0));
    }
}
